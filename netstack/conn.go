// Adapted from IEAmir/NetValve; Copyright 2026 The NetValve Authors. Apache-2.0.
package bridge
import (
	"net"
	"sync"
	"github.com/sagernet/gvisor/pkg/tcpip/adapters/gonet"
)
type tcpConn struct { Conn *gonet.TCPConn }
func (c *tcpConn) Read(p []byte) (int, error) { return c.Conn.Read(p) }
func (c *tcpConn) Write(p []byte) (int, error) { return c.Conn.Write(p) }
func (c *tcpConn) Close() error { return c.Conn.Close() }
type udpConn struct {
	conn *gonet.UDPConn
	readMu sync.Mutex
	readBuffer []byte
}
func (c *udpConn) Receive() ([]byte, error) {
	// Reuse the large datagram buffer per flow. Return an exact-sized owned slice so
	// queues do not retain 65 KiB of backing memory for every small UDP packet.
	c.readMu.Lock()
	defer c.readMu.Unlock()
	if c.readBuffer == nil {
		c.readBuffer = make([]byte, 65*1024)
	}
	n, err := c.conn.Read(c.readBuffer)
	if err != nil { return nil, err }
	packet := make([]byte, n)
	copy(packet, c.readBuffer[:n])
	return packet, nil
}
func (c *udpConn) Send(p []byte) error { _, err := c.conn.Write(p); return err }
func (c *udpConn) Close() error { return c.conn.Close() }
var (
	_ TCPConn = (*tcpConn)(nil)
	_ UDPConn = (*udpConn)(nil)
	_ net.Conn = (*gonet.TCPConn)(nil)
)
