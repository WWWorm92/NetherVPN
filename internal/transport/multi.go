package transport

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"net"
	"sync"
	"time"

	"github.com/WWWorm92/NetherVPN/internal/handshake"
	"github.com/WWWorm92/NetherVPN/internal/session"
	"github.com/flynn/noise"
)

var ErrCapacity = errors.New("transport: session capacity reached")

// Server is a limited experimental multi-client dispatcher. Do not expose
// publicly before adding handshake cookies, real revocation and rekeying.
type Server struct {
	conn *net.UDPConn
	identity noise.DHKey
	authorize func([]byte) bool
	mu sync.Mutex
	sessions map[uint64]*peerSession
	recent map[[32]byte]time.Time
	maxSessions int
	timeout time.Duration
}

type peerSession struct {
	secure *session.Session
	remote *net.UDPAddr
	lastSeen time.Time
}

// NewServer creates an experimental bounded UDP multiplexer.
func NewServer(conn *net.UDPConn, identity noise.DHKey, authorize func([]byte) bool, maxSessions int, timeout time.Duration) (*Server, error) {
	if conn == nil || authorize == nil || maxSessions < 1 || timeout <= 0 || len(identity.Private) != 32 || len(identity.Public) != 32 {
		return nil, ErrSession
	}
	return &Server{conn: conn,identity: identity,authorize: authorize,sessions:make(map[uint64]*peerSession),recent:make(map[[32]byte]time.Time),maxSessions:maxSessions,timeout:timeout},nil
}

var ErrSession = errors.New("transport: invalid server configuration")

// ServePacket handles one datagram from an address. It returns a response
// only on successful authentication and authorized dispatch.
func (s *Server) ServePacket(packet []byte, remote *net.UDPAddr, handler func([]byte) []byte) ([]byte,error) {
	if remote == nil || handler == nil { return nil,ErrSession }
	now:=time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()
	for id,p:=range s.sessions { if now.Sub(p.lastSeen)>s.timeout { delete(s.sessions,id) } }
	for digest,at:=range s.recent { if now.Sub(at)>s.timeout { delete(s.recent,digest) } }
	if len(packet)>=2 && packet[0]==wireInit {
		if len(packet)>4096 { return nil,ErrHandshakeFrame }
		digest:=sha256.Sum256(packet)
		if _,seen:=s.recent[digest];seen { return nil,ErrReplayHandshake }
		if len(s.sessions)>=s.maxSessions { return nil,ErrCapacity }
		r,err:=handshake.NewResponder(s.identity)
		if err!=nil { return nil,err }
		var params [sessionParametersSize]byte
		if _,err=rand.Read(params[:8]);err!=nil { return nil,err }
		binary.BigEndian.PutUint16(params[8:],1)
		id:=binary.BigEndian.Uint64(params[:8])
		if id==0 { return nil,ErrHandshakeFrame }
		if _,exists:=s.sessions[id];exists { return nil,ErrHandshakeFrame }
		response,out,in,err:=r.RespondWithPayload(packet[1:],s.authorize,params[:])
		if err!=nil { return nil,err }
		secure,err:=session.New(out,in,id,1)
		if err!=nil { return nil,err }
		s.sessions[id]=&peerSession{secure:secure,remote:remote,lastSeen:now}
		s.recent[digest]=now
		return append([]byte{wireResponse},response...),nil
	}
	h,err:=protocolHeader(packet)
	if err!=nil { return nil,err }
	p,exists:=s.sessions[h]
	if !exists { return nil,ErrUnknownSession }
	if !remote.IP.Equal(p.remote.IP)||remote.Port!=p.remote.Port { return nil,ErrUnknownSession }
	plain,err:=p.secure.Open(packet)
	if err!=nil { return nil,err }
	p.lastSeen=now
	reply,err:=p.secure.Seal(handler(plain))
	if err!=nil { return nil,err }
	return reply,nil
}

var (
	ErrReplayHandshake=errors.New("transport: repeated handshake initiation")
	ErrUnknownSession=errors.New("transport: unknown session")
)

func protocolHeader(packet []byte)(uint64,error){
	if len(packet)<36 || packet[0]!=1 || packet[1]!=3 { return 0,ErrHandshakeFrame }
	return binary.BigEndian.Uint64(packet[2:10]),nil
}

// ServeLoop continuously receives and dispatches UDP requests. The caller
// controls shutdown via conn.Close and should bound handler workload.
func (s *Server) ServeLoop(handler func([]byte) []byte) error {
	buf:=make([]byte,4096)
	for {
		n,remote,err:=s.conn.ReadFromUDP(buf)
		if err!=nil { return err }
		response,err:=s.ServePacket(bytes.Clone(buf[:n]),remote,handler)
		if err!=nil || len(response)==0 { continue }
		if len(response)>MaxDatagramSize { continue }
		_,_ = s.conn.WriteToUDP(response,remote)
	}
}
