package protocol

import "testing"

func TestReplayWindow(t *testing.T) {
 var w ReplayWindow
 cases := []struct{seq uint64; accept bool}{
  {10,true},{10,false},{8,true},{8,false},
  {9,true},{11,true},{75,true},{12,true},
  {11,false},{10,false},{75,false},{76,true},
  {12,false},{13,true},{13,false},
 }
 for i,c := range cases {
  if got := w.Accept(c.seq); got != c.accept {
   t.Fatalf("case %d seq=%d: got %v want %v",i,c.seq,got,c.accept)
  }
 }
}

func TestReplayWindowFarJump(t *testing.T) {
 var w ReplayWindow
 if !w.Accept(0) || !w.Accept(1000) || w.Accept(0) || w.Accept(1000) { t.Fatal("unexpected acceptance") }
 if !w.Accept(999) || w.Accept(999) { t.Fatal("reordered packet acceptance wrong") }
}
