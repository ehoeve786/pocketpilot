package ppnet

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
)

// testServer runs an SSH server on 127.0.0.1 that accepts clientKey or the password "secret", answers each exec
// with "ran: <command>" on stdout, "warn" on stderr and exit status 3, and sleeps for the command "sleep".
func testServer(t *testing.T, clientKey ssh.PublicKey) (port int, hostKey ssh.Signer) {
	t.Helper()
	_, hostPriv, _ := ed25519.GenerateKey(rand.Reader)
	hostKey, _ = ssh.NewSignerFromKey(hostPriv)
	config := &ssh.ServerConfig{
		PublicKeyCallback: func(_ ssh.ConnMetadata, key ssh.PublicKey) (*ssh.Permissions, error) {
			if clientKey != nil && string(key.Marshal()) == string(clientKey.Marshal()) {
				return nil, nil
			}
			return nil, errUnauthorized
		},
		PasswordCallback: func(_ ssh.ConnMetadata, password []byte) (*ssh.Permissions, error) {
			if string(password) == "secret" {
				return nil, nil
			}
			return nil, errUnauthorized
		},
	}
	config.AddHostKey(hostKey)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go serveConn(conn, config)
		}
	}()
	return listener.Addr().(*net.TCPAddr).Port, hostKey
}

var errUnauthorized = errors.New("not allowed")

func serveConn(conn net.Conn, config *ssh.ServerConfig) {
	_, chans, reqs, err := ssh.NewServerConn(conn, config)
	if err != nil {
		return
	}
	go ssh.DiscardRequests(reqs)
	for newChannel := range chans {
		channel, requests, err := newChannel.Accept()
		if err != nil {
			continue
		}
		go func() {
			defer channel.Close()
			for req := range requests {
				if req.Type != "exec" {
					req.Reply(false, nil)
					continue
				}
				length := binary.BigEndian.Uint32(req.Payload[:4])
				command := string(req.Payload[4 : 4+length])
				req.Reply(true, nil)
				if command == "sleep" {
					time.Sleep(3 * time.Second)
				}
				if command == "drop" {
					channel.Write([]byte("rebooting"))
					conn.Close()
					return
				}
				channel.Write([]byte("ran: " + command))
				channel.Stderr().Write([]byte("warn"))
				status := make([]byte, 4)
				binary.BigEndian.PutUint32(status, 3)
				channel.SendRequest("exit-status", false, status)
				return
			}
		}()
	}
}

func TestSshExecWithAGeneratedKey(t *testing.T) {
	private, err := SshGenerateKey()
	if err != nil {
		t.Fatal(err)
	}
	line, err := SshPublicKey(private)
	if err != nil || !strings.HasPrefix(line, "ssh-ed25519 ") || !strings.HasSuffix(line, " pocketpilot") {
		t.Fatalf("public key line %q, %v", line, err)
	}
	clientKey, _, _, _, err := ssh.ParseAuthorizedKey([]byte(line))
	if err != nil {
		t.Fatal(err)
	}
	port, hostKey := testServer(t, clientKey)

	result, err := SshExec("127.0.0.1", port, "comma", private, "", "", "uptime", 5000, nil)
	if err != nil {
		t.Fatal(err)
	}
	if result.ExitCode != 3 || result.Stdout != "ran: uptime" || result.Stderr != "warn" || result.TimedOut {
		t.Fatalf("unexpected result %+v", result)
	}
	if !strings.HasPrefix(result.HostKey, hostKey.PublicKey().Type()+" ") {
		t.Fatalf("host key %q", result.HostKey)
	}

	// The pinned key lets the next call through.
	if _, err := SshExec("127.0.0.1", port, "comma", private, "", result.HostKey, "uptime", 5000, nil); err != nil {
		t.Fatal(err)
	}
}

func TestSshExecRefusesAChangedHostKey(t *testing.T) {
	private, _ := SshGenerateKey()
	line, _ := SshPublicKey(private)
	clientKey, _, _, _, _ := ssh.ParseAuthorizedKey([]byte(line))
	port, _ := testServer(t, clientKey)
	_, err := SshExec("127.0.0.1", port, "comma", private, "", "ssh-ed25519 AAAAother", "uptime", 5000, nil)
	if err == nil || !strings.Contains(err.Error(), "key changed") {
		t.Fatalf("expected a changed-key error, got %v", err)
	}
}

func TestSshExecWithAPassword(t *testing.T) {
	port, _ := testServer(t, nil)
	result, err := SshExec("127.0.0.1", port, "pi", "", "secret", "", "ls", 5000, nil)
	if err != nil || result.Stdout != "ran: ls" {
		t.Fatalf("result %+v, %v", result, err)
	}
	if _, err := SshExec("127.0.0.1", port, "pi", "", "wrong", "", "ls", 5000, nil); err == nil {
		t.Fatal("a wrong password signed in")
	}
}

func TestSshExecTimesOut(t *testing.T) {
	port, _ := testServer(t, nil)
	started := time.Now()
	result, err := SshExec("127.0.0.1", port, "pi", "", "secret", "", "sleep", 1000, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !result.TimedOut || result.ExitCode != -1 || time.Since(started) > 2500*time.Millisecond {
		t.Fatalf("result %+v after %s", result, time.Since(started))
	}
}

func TestSshExecNeedsAWayToSignIn(t *testing.T) {
	if _, err := SshExec("127.0.0.1", 22, "pi", "", "", "", "ls", 1000, nil); err == nil {
		t.Fatal("expected an error without a key or password")
	}
}

type recordingSink struct {
	mu     sync.Mutex
	stdout strings.Builder
	stderr strings.Builder
}

func (r *recordingSink) Output(stderr bool, text string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if stderr {
		r.stderr.WriteString(text)
	} else {
		r.stdout.WriteString(text)
	}
}

func TestSshExecStreamsOutputToTheSink(t *testing.T) {
	port, _ := testServer(t, nil)
	sink := &recordingSink{}
	result, err := SshExec("127.0.0.1", port, "pi", "", "secret", "", "ls", 5000, sink)
	if err != nil {
		t.Fatal(err)
	}
	if sink.stdout.String() != "ran: ls" || sink.stderr.String() != "warn" || result.Stdout != "ran: ls" {
		t.Fatalf("sink %q / %q, result %+v", sink.stdout.String(), sink.stderr.String(), result)
	}
}

func TestSshExecKeepsOutputWhenTheConnectionDrops(t *testing.T) {
	port, _ := testServer(t, nil)
	result, err := SshExec("127.0.0.1", port, "pi", "", "secret", "", "drop", 5000, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !result.Closed || result.ExitCode != -1 || result.Stdout != "rebooting" {
		t.Fatalf("result %+v", result)
	}
}
