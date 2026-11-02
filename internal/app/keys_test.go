package app

import (
	"bytes"
	"encoding/hex"
	"errors"
	"testing"

	"github.com/WWWorm92/NetherVPN/internal/handshake"
)

func TestParseKey(t *testing.T) {
	identity,err:=handshake.GenerateIdentity()
	if err!=nil{t.Fatal(err)}
	parsed,err:=ParseIdentity(hex.EncodeToString(identity.Private))
	if err!=nil{t.Fatal(err)}
	if !bytes.Equal(parsed.Public,identity.Public){t.Fatal("public key derivation mismatch")}
	pub,err:=ParsePublic(hex.EncodeToString(identity.Public))
	if err!=nil || !bytes.Equal(pub,identity.Public){t.Fatal("public key mismatch")}
	if _,err:=ParseIdentity("garbage");!errors.Is(err,ErrKey){t.Fatal("bad key accepted")}
}
