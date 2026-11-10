package ppnet

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"sync"
	"time"
)

// newProxyServer serves HTTP that has already been decrypted by its listener, forwarding every
// request to the app's loopback-only remote MCP port, 127.0.0.1:targetPort. Each request adds a line
// to logs and, when onRequest is not nil, calls it.
func newProxyServer(targetPort int, logs *ring, onRequest func()) *http.Server {
	target := &url.URL{Scheme: "http", Host: fmt.Sprintf("127.0.0.1:%d", targetPort)}
	proxy := &httputil.ReverseProxy{
		Rewrite: func(r *httputil.ProxyRequest) {
			r.SetURL(target)
			// Keep the public host name: the app checks it and builds OAuth URLs from it.
			r.Out.Host = r.In.Host
			r.SetXForwarded()
		},
		// Stream MCP's server-sent events without buffering.
		FlushInterval: -1,
		ErrorHandler: func(w http.ResponseWriter, _ *http.Request, err error) {
			http.Error(w, "PocketPilot's server is not running on the phone", http.StatusBadGateway)
		},
	}
	return &http.Server{
		Handler:           logRequests(logs, onRequest, proxy),
		ReadHeaderTimeout: 30 * time.Second,
		// MCP and OAuth requests carry a bearer token and little else. Go's default of 1 MB lets a client that has
		// not signed in make the phone read and log that much per request.
		MaxHeaderBytes: maxHeaderBytes,
		// Bounds idle keep-alive connections, so a client that stops sending does not hold one open forever. An open
		// event stream that is still being read is not cut by this.
		IdleTimeout: 10 * time.Minute,
		// TLS is already terminated by the listener.
		TLSNextProto: map[string]func(*http.Server, *tls.Conn, http.Handler){},
	}
}

// logRequests adds a line per request to the log (method, path, status, time taken), so the owner
// can copy it when a client such as claude.ai cannot connect. Tokens and bodies are not logged.
//
// onRequest is called once for a request the app answered without an error status. A refused request, such as a
// scanner's without a token, does not count: Funnel's auto-off would otherwise never come due on a public name.
//
// A request, an event stream included, ends after maxRequestTime. A client that vanished therefore cannot hold a stream
// open for good; a client that still needs the stream reconnects.
func logRequests(logs *ring, onRequest func(), next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		ctx, cancel := context.WithTimeout(r.Context(), maxRequestTime)
		defer cancel()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK, onAnswered: onRequest}
		next.ServeHTTP(rec, r.WithContext(ctx))
		logs.logf("ppnet: %s %s %s -> %d in %v (%s)", r.Method, r.Host, r.URL.Path, rec.status,
			time.Since(start).Round(time.Millisecond), r.UserAgent())
	})
}

// maxRequestTime is how long one request may run. A variable so a test can shorten it.
var maxRequestTime = 30 * time.Minute

// maxHeaderBytes bounds a request's line and headers.
const maxHeaderBytes = 32 << 10

// maxConnections bounds the connections one listener serves at a time. A handful of AI clients need a few each; the
// rest of the internet, when Funnel is on, cannot hold more than this many open.
const maxConnections = 64

type statusRecorder struct {
	http.ResponseWriter
	status int
	// onAnswered is called when the first status is written and is not an error. It runs then, not when the
	// request ends, so a long event stream counts from its start.
	onAnswered func()
	wrote      bool
}

func (r *statusRecorder) WriteHeader(code int) {
	// 1xx statuses are interim: the real one follows.
	if !r.wrote && code >= 200 {
		r.wrote = true
		r.status = code
		if code < 400 && r.onAnswered != nil {
			r.onAnswered()
		}
	}
	r.ResponseWriter.WriteHeader(code)
}

func (r *statusRecorder) Write(b []byte) (int, error) {
	if !r.wrote {
		r.WriteHeader(http.StatusOK)
	}
	return r.ResponseWriter.Write(b)
}

// Unwrap lets http.ResponseController reach Flush, which streaming MCP responses need.
func (r *statusRecorder) Unwrap() http.ResponseWriter { return r.ResponseWriter }

// limitConns bounds how many connections accepted from ln are open at once; Accept waits while that many are.
func limitConns(ln net.Listener, max int) net.Listener {
	return &limitedListener{Listener: ln, slots: make(chan struct{}, max), closed: make(chan struct{})}
}

type limitedListener struct {
	net.Listener
	slots     chan struct{}
	closed    chan struct{}
	closeOnce sync.Once
}

func (l *limitedListener) Accept() (net.Conn, error) {
	select {
	case l.slots <- struct{}{}:
	case <-l.closed:
		return nil, net.ErrClosed
	}
	conn, err := l.Listener.Accept()
	if err != nil {
		<-l.slots
		return nil, err
	}
	return &limitedConn{Conn: conn, release: func() { <-l.slots }}, nil
}

func (l *limitedListener) Close() error {
	l.closeOnce.Do(func() { close(l.closed) })
	return l.Listener.Close()
}

type limitedConn struct {
	net.Conn
	release   func()
	closeOnce sync.Once
}

func (c *limitedConn) Close() error {
	c.closeOnce.Do(c.release)
	return c.Conn.Close()
}
