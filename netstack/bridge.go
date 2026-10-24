// Adapted from the gVisor bridge design in IEAmir/NetValve.
// Copyright 2026 The NetValve Authors; see NOTICE.md. Apache-2.0.
package bridge
import (
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"
	"github.com/sagernet/gvisor/pkg/tcpip"
	"github.com/sagernet/gvisor/pkg/tcpip/adapters/gonet"
	"github.com/sagernet/gvisor/pkg/tcpip/header"
	"github.com/sagernet/gvisor/pkg/tcpip/link/fdbased"
	"github.com/sagernet/gvisor/pkg/tcpip/network/ipv4"
	"github.com/sagernet/gvisor/pkg/tcpip/network/ipv6"
	"github.com/sagernet/gvisor/pkg/tcpip/stack"
	"github.com/sagernet/gvisor/pkg/tcpip/transport/tcp"
	"github.com/sagernet/gvisor/pkg/tcpip/transport/udp"
	"github.com/sagernet/gvisor/pkg/waiter"
)
const nicID tcpip.NICID = 1
type Handler interface {
	HandleTCP(srcIP string, srcPort int, dstIP string, dstPort int, conn TCPConn)
	HandleUDP(srcIP string, srcPort int, dstIP string, dstPort int, conn UDPConn)
	Log(level int, msg string)
}
type TCPConn interface { Read(p []byte) (int, error); Write(p []byte) (int, error); Close() error }
type UDPConn interface { Receive() ([]byte, error); Send(p []byte) error; Close() error }
type Tunnel struct {
	stack *stack.Stack
	handler Handler
	mu sync.Mutex
	closed bool
	stopCh chan struct{}
	tcpFlows int64
	udpFlows int64
}
func (t *Tunnel) logf(level int, format string, a ...interface{}) {
	defer func() { _ = recover() }()
	if t.handler != nil { t.handler.Log(level, fmt.Sprintf(format, a...)) }
}
func NewTunnel(tunFd int, mtu int, ipv6Mode string, handler Handler) (*Tunnel, error) {
	if handler == nil { return nil, errors.New("nil handler") }
	linkEP, err := fdbased.New(&fdbased.Options{FDs: []int{tunFd}, MTU: uint32(mtu)})
	if err != nil { return nil, fmt.Errorf("fdbased.New: %w", err) }
	s := stack.New(stack.Options{
		NetworkProtocols: []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
		HandleLocal: false,
	})
	if e := s.CreateNIC(nicID, linkEP); e != nil { return nil, fmt.Errorf("CreateNIC: %v", e) }
	if e := s.SetPromiscuousMode(nicID, true); e != nil { return nil, fmt.Errorf("SetPromiscuousMode: %v", e) }
	if e := s.SetSpoofing(nicID, true); e != nil { return nil, fmt.Errorf("SetSpoofing: %v", e) }
	s.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: nicID},
		{Destination: header.IPv6EmptySubnet, NIC: nicID},
	})
	t := &Tunnel{stack: s, handler: handler, stopCh: make(chan struct{})}
	t.installTCPForwarder()
	t.installUDPForwarder()
	t.logf(1, "netstack started (mtu=%d ipv6=%s)", mtu, ipv6Mode)
	go t.livenessLoop()
	return t, nil
}
func (t *Tunnel) livenessLoop() {
	defer func() { _ = recover() }()
	ticker := time.NewTicker(5*time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-t.stopCh: return
		case <-ticker.C:
			t.logf(0, "netstack alive: tcpFlows=%d udpFlows=%d", atomic.LoadInt64(&t.tcpFlows), atomic.LoadInt64(&t.udpFlows))
		}
	}
}
func (t *Tunnel) Stop() error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed { return nil }
	t.closed = true
	close(t.stopCh)
	t.logf(1, "netstack stopping")
	// Close transport endpoints, then wait for link endpoint workers to exit.
	// fdbased.New does not own the TUN FD; the Android service closes it only
	// after this method returns. Waiting here prevents native workers from
	// touching an FD that has already been closed/reused during a VPN restart.
	t.stack.Close()
	t.stack.Wait()
	return nil
}
func (t *Tunnel) installTCPForwarder() {
	fwd := tcp.NewForwarder(t.stack, 0, 2048, func(r *tcp.ForwarderRequest) {
		defer func() { if rec := recover(); rec != nil { t.logf(3, "TCP forwarder panic: %v", rec) } }()
		id := r.ID()
		var wq waiter.Queue
		ep, err := r.CreateEndpoint(&wq)
		if err != nil { t.logf(2, "TCP endpoint failed: %v", err); r.Complete(true); return }
		r.Complete(false)
		atomic.AddInt64(&t.tcpFlows, 1)
		t.handler.HandleTCP(id.RemoteAddress.String(), int(id.RemotePort), id.LocalAddress.String(), int(id.LocalPort), &tcpConn{Conn: gonet.NewTCPConn(&wq, ep)})
	})
	t.stack.SetTransportProtocolHandler(tcp.ProtocolNumber, fwd.HandlePacket)
}
func (t *Tunnel) installUDPForwarder() {
	fwd := udp.NewForwarder(t.stack, func(r *udp.ForwarderRequest) (handled bool) {
		defer func() { if rec := recover(); rec != nil { t.logf(3, "UDP forwarder panic: %v", rec); handled = false } }()
		id := r.ID()
		var wq waiter.Queue
		ep, err := r.CreateEndpoint(&wq)
		if err != nil { t.logf(2, "UDP endpoint failed: %v", err); return false }
		atomic.AddInt64(&t.udpFlows, 1)
		t.handler.HandleUDP(id.RemoteAddress.String(), int(id.RemotePort), id.LocalAddress.String(), int(id.LocalPort), &udpConn{conn: gonet.NewUDPConn(&wq, ep)})
		return true
	})
	t.stack.SetTransportProtocolHandler(udp.ProtocolNumber, fwd.HandlePacket)
}
