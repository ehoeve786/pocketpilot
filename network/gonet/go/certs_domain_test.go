package ppnet

import (
	"strings"
	"testing"
)

func TestConfigureRefusesANameThatIsNotAHost(t *testing.T) {
	c := NewCertManager(memBlobs{})
	c.Configure("not a domain", "owner@example.com", "token")
	if c.domain != "" {
		t.Fatalf("domain kept: %q", c.domain)
	}
	if !strings.Contains(c.Status(), "is not a domain name") {
		t.Fatalf("no explanation in status: %s", c.Status())
	}
}

func TestConfigureKeepsAValidHostName(t *testing.T) {
	c := NewCertManager(memBlobs{})
	c.Configure("Phone.Example.com.", "owner@example.com", "token")
	if c.domain != "phone.example.com" {
		t.Fatalf("domain = %q", c.domain)
	}
}

func TestForgetDropsTheCloudflareToken(t *testing.T) {
	c := NewCertManager(memBlobs{})
	c.Configure("phone.example.com", "owner@example.com", "secret-token")
	c.Forget()
	if c.token != "" {
		t.Fatal("the Cloudflare token stayed in memory after Forget")
	}
}
