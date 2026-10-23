// Adapted from IEAmir/NetValve; Copyright 2026 The NetValve Authors. Apache-2.0.
package bridge
import (
	"net"
	"time"
	"github.com/sagernet/gvisor/pkg/tcpip/adapters/gonet"
)
type tcpConn struct { Conn *gonet.TCPConn }
func (c *tcpConn) Read(p []byte) (int, error) { return c.Conn.Read(p) }
func (c *tcpConn) Write(p []byte) (int, error) { return c.Conn.Write(p) }
func (c *tcpConn) Close() error { return c.Conn.Close() }
type udpConn struct { conn *gonet.UDPConn }
func (c *udpConn) Receive() ([]byte, error) {
	buf := make([]byte, 65*1024)
	_ = c.conn.SetReadDeadline(time.Now().Add(60*time.Second))
	n, err := c.conn.Read(buf)
	if err != nil { return nil, err }
	return buf[:n], nil
}
func (c *udpConn) Send(p []byte) error { _, err := c.conn.Write(p); return err }
func (c *udpConn) Close() error { return c.conn.Close() }
var (
	_ TCPConn = (*tcpConn)(nil)
	_ UDPConn = (*udpConn)(nil)
	_ net.Conn = (*gonet.TCPConn)(nil)
)
