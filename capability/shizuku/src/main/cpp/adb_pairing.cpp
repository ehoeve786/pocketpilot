// SPAKE2 and AES-128-GCM for adb wireless pairing, as in adb's pairing_auth.cpp. Adapted from Shizuku
// (Apache License 2.0): https://github.com/thedjchi/Shizuku/blob/master/manager/src/main/jni/adb_pairing.cpp

#include <jni.h>
#include <android/log.h>
#include <cinttypes>
#include <cstdlib>
#include <cstring>
#include <openssl/aead.h>
#include <openssl/curve25519.h>
#include <openssl/digest.h>
#include <openssl/evp.h>
#include <openssl/hkdf.h>

#define LOG_TAG "PocketPilotAdb"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const uint8_t kClientName[] = "adb pair client";
static const uint8_t kServerName[] = "adb pair server";
static constexpr size_t kHkdfKeyLength = 16;
static constexpr jsize kMaxPayload = 16384;

struct PairingContext {
    SPAKE2_CTX *spake2;
    uint8_t message[SPAKE2_MAX_MSG_SIZE];
    size_t message_size;
    EVP_AEAD_CTX *aead;
    uint64_t encrypt_sequence;
    uint64_t decrypt_sequence;
};

static jlong Create(JNIEnv *env, jclass, jbyteArray jPassword) {
    auto spake2 = SPAKE2_CTX_new(spake2_role_alice, kClientName, sizeof(kClientName), kServerName, sizeof(kServerName));
    if (spake2 == nullptr) {
        LOGE("SPAKE2_CTX_new failed");
        return 0;
    }
    auto ctx = static_cast<PairingContext *>(calloc(1, sizeof(PairingContext)));
    if (ctx == nullptr) {
        SPAKE2_CTX_free(spake2);
        return 0;
    }
    ctx->spake2 = spake2;

    auto size = env->GetArrayLength(jPassword);
    auto password = env->GetByteArrayElements(jPassword, nullptr);
    int ok = SPAKE2_generate_msg(spake2, ctx->message, &ctx->message_size, sizeof(ctx->message),
                                 reinterpret_cast<const uint8_t *>(password), static_cast<size_t>(size));
    env->ReleaseByteArrayElements(jPassword, password, JNI_ABORT);
    if (ok != 1 || ctx->message_size == 0) {
        LOGE("SPAKE2_generate_msg failed");
        SPAKE2_CTX_free(spake2);
        free(ctx);
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

static jbyteArray Message(JNIEnv *env, jobject, jlong pointer) {
    auto ctx = reinterpret_cast<PairingContext *>(pointer);
    auto out = env->NewByteArray(static_cast<jsize>(ctx->message_size));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(ctx->message_size), reinterpret_cast<const jbyte *>(ctx->message));
    return out;
}

static jboolean InitCipher(JNIEnv *env, jobject, jlong pointer, jbyteArray jTheirs) {
    auto ctx = reinterpret_cast<PairingContext *>(pointer);
    auto size = env->GetArrayLength(jTheirs);
    if (size <= 0 || size > static_cast<jsize>(SPAKE2_MAX_MSG_SIZE) || ctx->aead != nullptr) return JNI_FALSE;

    uint8_t theirs[SPAKE2_MAX_MSG_SIZE];
    env->GetByteArrayRegion(jTheirs, 0, size, reinterpret_cast<jbyte *>(theirs));

    uint8_t material[SPAKE2_MAX_KEY_SIZE];
    size_t material_size = 0;
    if (SPAKE2_process_msg(ctx->spake2, material, &material_size, sizeof(material), theirs, static_cast<size_t>(size)) != 1) {
        LOGE("SPAKE2_process_msg failed");
        return JNI_FALSE;
    }

    uint8_t key[kHkdfKeyLength];
    static const uint8_t info[] = "adb pairing_auth aes-128-gcm key";
    if (HKDF(key, sizeof(key), EVP_sha256(), material, material_size, nullptr, 0, info, sizeof(info) - 1) != 1) {
        LOGE("HKDF failed");
        return JNI_FALSE;
    }
    ctx->aead = EVP_AEAD_CTX_new(EVP_aead_aes_128_gcm(), key, sizeof(key), EVP_AEAD_DEFAULT_TAG_LENGTH);
    return ctx->aead != nullptr ? JNI_TRUE : JNI_FALSE;
}

/** Seals or opens [jIn] with the next nonce: the sequence number, little-endian, then zeros. */
static jbyteArray Crypt(JNIEnv *env, jlong pointer, jbyteArray jIn, bool seal) {
    auto ctx = reinterpret_cast<PairingContext *>(pointer);
    if (ctx->aead == nullptr) return nullptr;
    auto in_size = env->GetArrayLength(jIn);
    if (in_size <= 0 || in_size > kMaxPayload) return nullptr;

    auto aead = EVP_AEAD_CTX_aead(ctx->aead);
    size_t nonce_size = EVP_AEAD_nonce_length(aead);
    uint8_t nonce[EVP_AEAD_MAX_NONCE_LENGTH] = {};
    uint64_t *sequence = seal ? &ctx->encrypt_sequence : &ctx->decrypt_sequence;
    memcpy(nonce, sequence, sizeof(*sequence));

    size_t out_capacity = static_cast<size_t>(in_size) + (seal ? EVP_AEAD_max_overhead(aead) : 0);
    auto out = static_cast<uint8_t *>(malloc(out_capacity));
    auto in = static_cast<uint8_t *>(malloc(static_cast<size_t>(in_size)));
    if (out == nullptr || in == nullptr) {
        free(out);
        free(in);
        return nullptr;
    }
    env->GetByteArrayRegion(jIn, 0, in_size, reinterpret_cast<jbyte *>(in));

    size_t written = 0;
    int ok = seal
             ? EVP_AEAD_CTX_seal(ctx->aead, out, &written, out_capacity, nonce, nonce_size, in, static_cast<size_t>(in_size), nullptr, 0)
             : EVP_AEAD_CTX_open(ctx->aead, out, &written, out_capacity, nonce, nonce_size, in, static_cast<size_t>(in_size), nullptr, 0);
    free(in);

    jbyteArray result = nullptr;
    if (ok == 1) {
        ++*sequence;
        result = env->NewByteArray(static_cast<jsize>(written));
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(written), reinterpret_cast<const jbyte *>(out));
    } else {
        LOGE("%s failed", seal ? "seal" : "open");
    }
    free(out);
    return result;
}

static jbyteArray Encrypt(JNIEnv *env, jobject, jlong pointer, jbyteArray jIn) {
    return Crypt(env, pointer, jIn, true);
}

static jbyteArray Decrypt(JNIEnv *env, jobject, jlong pointer, jbyteArray jIn) {
    return Crypt(env, pointer, jIn, false);
}

static void Destroy(JNIEnv *, jobject, jlong pointer) {
    auto ctx = reinterpret_cast<PairingContext *>(pointer);
    SPAKE2_CTX_free(ctx->spake2);
    if (ctx->aead != nullptr) EVP_AEAD_CTX_free(ctx->aead);
    free(ctx);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return -1;

    auto clazz = env->FindClass("app/pocketpilot/capability/shizuku/adb/PairingContext");
    if (clazz == nullptr) return -1;

    const JNINativeMethod methods[] = {
            {"nativeCreate",     "([B)J",   reinterpret_cast<void *>(Create)},
            {"nativeMessage",    "(J)[B",   reinterpret_cast<void *>(Message)},
            {"nativeInitCipher", "(J[B)Z",  reinterpret_cast<void *>(InitCipher)},
            {"nativeEncrypt",    "(J[B)[B", reinterpret_cast<void *>(Encrypt)},
            {"nativeDecrypt",    "(J[B)[B", reinterpret_cast<void *>(Decrypt)},
            {"nativeDestroy",    "(J)V",    reinterpret_cast<void *>(Destroy)},
    };
    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) return -1;
    return JNI_VERSION_1_6;
}
