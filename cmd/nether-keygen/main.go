package main

import (
	"encoding/hex"
	"fmt"
	"os"

	"github.com/WWWorm92/NetherVPN/internal/handshake"
)

func main() {
	key, err := handshake.GenerateIdentity()
	if err != nil { fmt.Fprintln(os.Stderr,err);os.Exit(1) }
	fmt.Printf("PRIVATE=%s\nPUBLIC=%s\n",hex.EncodeToString(key.Private),hex.EncodeToString(key.Public))
}
