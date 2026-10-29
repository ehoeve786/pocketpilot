package ppnet

import (
	"net/http"
	"net/http/httptest"
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
