package ppnet

import (
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestRequestsEndAfterTheLimit(t *testing.T) {
	old := maxRequestTime
	maxRequestTime = 50 * time.Millisecond
	defer func() { maxRequestTime = old }()
	handler := logRequests(newRing(10), nil, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-r.Context().Done()
	}))
	done := make(chan struct{})
	go func() {
		handler.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/", nil))
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("a request outlived the limit")
	}
}

func TestOnlyAnsweredRequestsCount(t *testing.T) {
	counted := 0
	status := http.StatusUnauthorized
	handler := logRequests(newRing(10), func() { counted++ }, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(status)
	}))
	handler.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/mcp", nil))
	if counted != 0 {
		t.Fatal("a refused request counted as use")
	}
	status = http.StatusOK
	handler.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/mcp", nil))
	if counted != 1 {
		t.Fatalf("an answered request counted %d times", counted)
	}
}

func TestLogLinesAreBoundedAndStayOneLine(t *testing.T) {
	logs := newRing(10)
	handler := logRequests(logs, nil, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	// As the server decodes it: a path of %0a holds a line break.
	req.URL.Path = "/" + strings.Repeat("a", 100_000) + "\nppnet: forged"
	handler.ServeHTTP(httptest.NewRecorder(), req)
	got := logs.String()
	if len(got) > maxLineBytes+64 {
		t.Fatalf("a log line of %d bytes was kept", len(got))
	}
	if strings.Contains(got, "\n") {
		t.Fatal("a request added a line of its own to the log")
	}
}

func TestConnectionsAreLimited(t *testing.T) {
	inner, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ln := limitConns(inner, 1)
	defer ln.Close()
	accepted := make(chan net.Conn, 2)
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			accepted <- conn
		}
	}()
	for i := 0; i < 2; i++ {
		c, err := net.Dial("tcp", inner.Addr().String())
		if err != nil {
			t.Fatal(err)
		}
		defer c.Close()
	}
	first := <-accepted
	select {
	case <-accepted:
		t.Fatal("a second connection was served while the first was open")
	case <-time.After(200 * time.Millisecond):
	}
	first.Close()
	select {
	case c := <-accepted:
		c.Close()
	case <-time.After(5 * time.Second):
		t.Fatal("the waiting connection was not served after the first closed")
	}
}
