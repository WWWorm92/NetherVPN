package protocol

// ReplayWindow tracks the highest *authenticated* counter and the preceding
// 63 positions. Each key epoch and receive direction needs its own window.
// A caller MUST call Accept only AFTER successful AEAD verification.
type ReplayWindow struct {
 max uint64
 bits uint64
 initialized bool
}

// Accept reports whether an already-authenticated packet counter is new.
// False means duplicate or outside the 64-position window.
func (w *ReplayWindow) Accept(counter uint64) bool {
 if !w.initialized {
  w.initialized = true
  w.max = counter
  w.bits = 1
  return true
 }
 if counter > w.max {
  delta := counter - w.max
  if delta >= 64 {
   w.bits = 1
  } else {
   w.bits = (w.bits << delta) | 1
  }
  w.max = counter
  return true
 }
 diff := w.max - counter
 if diff >= 64 { return false }
 mask := uint64(1) << diff
 if w.bits & mask != 0 { return false }
 w.bits |= mask
 return true
}
