package protocol

import "testing"

func TestVersionIsPreRelease(t *testing.T) {
 if VersionMajor != 0 { t.Fatal("pre-alpha protocol must not claim version 1") }
}
