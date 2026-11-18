package ppnet

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"time"

	"golang.org/x/crypto/ssh"
)

// SshResult is what one command on a server returned. HostKey is the server's key (type and base64), which the
// app pins on the first connection and passes back as pinnedHostKey afterwards.
type SshResult struct {
	ExitCode int
	Stdout   string
	Stderr   string
	TimedOut bool
	// Closed is true when the connection ended before the command reported how it ended, as when it reboots
	// the server.
	Closed  bool
	HostKey string
}

// sshOutputMax bounds each of stdout and stderr, so a chatty command cannot fill the app's memory.
const sshOutputMax = 64 * 1024

// SshGenerateKey returns a new ed25519 private key in OpenSSH PEM format, for the app's secret store.
func SshGenerateKey() (string, error) {
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return "", err
	}
	block, err := ssh.MarshalPrivateKey(priv, "pocketpilot")
	if err != nil {
		return "", err
	}
	return string(pem.EncodeToMemory(block)), nil
}

// SshPublicKey returns the authorized_keys line for privateKey, ending in the comment "pocketpilot".
func SshPublicKey(privateKey string) (string, error) {
	signer, err := ssh.ParsePrivateKey([]byte(privateKey))
	if err != nil {
		return "", err
	}
	line := strings.TrimSpace(string(ssh.MarshalAuthorizedKey(signer.PublicKey())))
	return line + " pocketpilot", nil
}

// SshSink receives a command's output as it arrives, for showing it live. Calls can come from two goroutines at
// once, one for each stream, so the app's side must be thread-safe.
type SshSink interface {
	Output(stderr bool, text string)
}

// SshExec runs command on host:port as user and returns its output. Signs in with privateKey when it is not
// empty and with password when that is not empty. With pinnedHostKey empty, any host key is accepted and returned
// in the result for the app to pin; otherwise a different key fails the call before anything is sent.
// When sink is not nil, it gets every piece of output as it arrives; the result still holds all of it.
func SshExec(host string, port int, user, privateKey, password, pinnedHostKey, command string, timeoutMs int, sink SshSink) (*SshResult, error) {
	if host == "" || user == "" || command == "" {
		return nil, errors.New("host, user and command are required")
	}
	if port <= 0 || port > 65535 {
		port = 22
	}
	timeout := time.Duration(timeoutMs) * time.Millisecond
	if timeout <= 0 {
		timeout = 30 * time.Second
	}

	var auth []ssh.AuthMethod
	if privateKey != "" {
		signer, err := ssh.ParsePrivateKey([]byte(privateKey))
		if err != nil {
			return nil, fmt.Errorf("the key is not readable: %w", err)
		}
		auth = append(auth, ssh.PublicKeys(signer))
	}
	if password != "" {
		auth = append(auth, ssh.Password(password))
		// Some servers ask for the password through keyboard-interactive instead.
		auth = append(auth, ssh.KeyboardInteractive(func(_, _ string, questions []string, _ []bool) ([]string, error) {
			answers := make([]string, len(questions))
			for i := range answers {
				answers[i] = password
			}
			return answers, nil
		}))
	}
	if len(auth) == 0 {
		return nil, errors.New("no key or password to sign in with")
	}

	result := &SshResult{}
	config := &ssh.ClientConfig{
		User: user,
		Auth: auth,
		HostKeyCallback: func(_ string, _ net.Addr, key ssh.PublicKey) error {
			seen := key.Type() + " " + base64.StdEncoding.EncodeToString(key.Marshal())
			result.HostKey = seen
			if pinnedHostKey != "" && seen != pinnedHostKey {
				return errors.New("the server's key changed since PocketPilot first connected; if that is expected, forget its key in Settings")
			}
			return nil
		},
		Timeout: 10 * time.Second,
	}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	address := net.JoinHostPort(host, strconv.Itoa(port))
	dialer := net.Dialer{Timeout: config.Timeout}
	conn, err := dialer.DialContext(ctx, "tcp", address)
	if err != nil {
		return nil, fmt.Errorf("could not reach %s: %w", address, err)
	}
	// The handshake and the command both stop at the deadline.
	stop := context.AfterFunc(ctx, func() { conn.Close() })
	defer stop()
	sshConn, chans, reqs, err := ssh.NewClientConn(conn, address, config)
	if err != nil {
		conn.Close()
		if ctx.Err() != nil {
			return nil, fmt.Errorf("%s did not answer within %s", address, timeout)
		}
		return nil, fmt.Errorf("could not sign in to %s: %w", address, err)
	}
	client := ssh.NewClient(sshConn, chans, reqs)
	defer client.Close()

	session, err := client.NewSession()
	if err != nil {
		return nil, fmt.Errorf("could not open a session: %w", err)
	}
	defer session.Close()
	stdout := cappedBuffer{sink: sink}
	stderr := cappedBuffer{sink: sink, stderr: true}
	session.Stdout = &stdout
	session.Stderr = &stderr

	if err := session.Start(command); err != nil {
		return nil, fmt.Errorf("the command did not run: %w", err)
	}
	err = session.Wait()
	result.Stdout = stdout.String()
	result.Stderr = stderr.String()
	if ctx.Err() != nil {
		result.TimedOut = true
		result.ExitCode = -1
		return result, nil
	}
	var exit *ssh.ExitError
	switch {
	case err == nil:
		result.ExitCode = 0
	case errors.As(err, &exit):
		result.ExitCode = exit.ExitStatus()
	default:
		// The command started but the connection went away, as when it reboots the server: what it printed
		// so far is kept.
		result.ExitCode = -1
		result.Closed = true
	}
	return result, nil
}

// cappedBuffer keeps the first sshOutputMax bytes and drops the rest, so the command never blocks on a full pipe.
type cappedBuffer struct {
	buf    bytes.Buffer
	sink   SshSink
	stderr bool
}

func (c *cappedBuffer) Write(p []byte) (int, error) {
	if c.sink != nil && len(p) > 0 {
		c.sink.Output(c.stderr, string(p))
	}
	if room := sshOutputMax - c.buf.Len(); room > 0 {
		if len(p) > room {
			c.buf.Write(p[:room])
		} else {
			c.buf.Write(p)
		}
	}
	return len(p), nil
}

func (c *cappedBuffer) String() string { return c.buf.String() }
