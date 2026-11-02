package app

import (
	"crypto/ecdh"
	"encoding/hex"
	"errors"

	"github.com/flynn/noise"
)

var ErrKey = errors.New("app: invalid X25519 key material")

func ParseIdentity(privateHex string) (noise.DHKey, error) {
	raw, err := hex.DecodeString(privateHex)
	if err != nil || len(raw) != 32 { return noise.DHKey{}, ErrKey }
	key, err := ecdh.X25519().NewPrivateKey(raw)
	if err != nil { return noise.DHKey{}, ErrKey }
	return noise.DHKey{Private: key.Bytes(), Public: key.PublicKey().Bytes()}, nil
}

func ParsePublic(publicHex string) ([]byte,error) {
	raw,err:=hex.DecodeString(publicHex)
	if err!=nil || len(raw)!=32 { return nil,ErrKey }
	if _,err=ecdh.X25519().NewPublicKey(raw);err!=nil{return nil,ErrKey}
	return raw,nil
}
