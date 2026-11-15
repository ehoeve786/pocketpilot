# Shizuku starts the privileged service by class name in its own process.
-keep class app.pocketpilot.capability.shizuku.PrivilegedService { <init>(...); }

# PocketPilot's own privileged process starts by class name through app_process.
-keep class app.pocketpilot.capability.shizuku.PrivilegedMain { public static void main(java.lang.String[]); }

# libpocketpilot_adb.so registers its methods on this class by name.
-keep class app.pocketpilot.capability.shizuku.adb.PairingContext {
    native <methods>;
}
