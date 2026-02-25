package candy_mobile

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/lunixbochs/struc"
	"golang.org/x/net/proxy"
)

// Protocol Constants
const (
	auth      uint8 = 0
	forward   uint8 = 1
	dhcp_kind uint8 = 2
	peer_kind uint8 = 3
	vmac_kind uint8 = 4
	discovery uint8 = 5
	general   uint8 = 255
)

// LogListener allows the Android side to receive logs from Go
type LogListener interface {
	OnLog(msg string)
}

var (
	wsConn       *websocket.Conn
	wsWriteMutex sync.Mutex
	connMutex    sync.Mutex
	password     string
	lastConfig   string

	candyOS       = "android"
	candyVersion  = "v52.3-stable"
	candyHostname = "android-device"

	globalVMac    string
	logger        LogListener
	stopHeartbeat chan struct{}
	proxyUrl      string
)

func init() {
	bytes := make([]byte, 8)
	rand.Read(bytes)
	globalVMac = hex.EncodeToString(bytes)
	if len(globalVMac) != 16 {
		globalVMac = fmt.Sprintf("%016x", time.Now().UnixNano())[:16]
	}
}

// Minimal Structs matching C++ Protocol
type VMacMessage struct {
	Type      uint8    `struc:"uint8"`
	VMac      string   `struc:"[16]byte"`
	Timestamp int64    `struc:"int64"`
	Hash      [32]byte `struc:"[32]byte"`
}

type ExptTunMessage struct {
	Type      uint8    `struc:"uint8"`
	Timestamp int64    `struc:"int64"`
	Cidr      [32]byte `struc:"[32]byte"`
	Hash      [32]byte `struc:"[32]byte"`
}

type AuthMessage struct {
	Type      uint8    `struc:"uint8"`
	IP        uint32   `struc:"uint32"`
	Timestamp int64    `struc:"int64"`
	Hash      [32]byte `struc:"[32]byte"`
}

type DiscoveryMessage struct {
	Type uint8  `struc:"uint8"`
	Src  uint32 `struc:"uint32"`
	Dst  uint32 `struc:"uint32"`
}

func (v *VMacMessage) UpdateHash(pass string) {
	data := append([]byte(pass), []byte(v.VMac)...)
	tsBuf := make([]byte, 8)
	binary.BigEndian.PutUint64(tsBuf, uint64(v.Timestamp))
	hash := sha256.Sum256(append(data, tsBuf...))
	copy(v.Hash[:], hash[:])
}

func (e *ExptTunMessage) UpdateHash(pass string) {
	data := []byte(pass)
	tsBuf := make([]byte, 8)
	binary.BigEndian.PutUint64(tsBuf, uint64(e.Timestamp))
	hash := sha256.Sum256(append(data, tsBuf...))
	copy(e.Hash[:], hash[:])
}

func (a *AuthMessage) UpdateHash(pass string) {
	data := append([]byte(pass), binary.BigEndian.AppendUint32(nil, a.IP)...)
	tsBuf := make([]byte, 8)
	binary.BigEndian.PutUint64(tsBuf, uint64(a.Timestamp))
	hash := sha256.Sum256(append(data, tsBuf...))
	copy(a.Hash[:], hash[:])
}

func SetPassword(p string) { password = p }

func SetVMac(vmac string) {
	if len(vmac) == 16 {
		globalVMac = vmac
	}
}

func SetSystemInfo(osName, version, hostname string) {
	if osName != "" {
		candyOS = osName
	}
	if version != "" {
		candyVersion = version
	}
	if hostname != "" {
		candyHostname = hostname
	}
}

func SetProxy(url string) {
	proxyUrl = url
}

func logToMobile(msg string) {
	if logger != nil {
		logger.OnLog(msg)
	}
}

func SetLogListener(l LogListener) { logger = l }

func writeSafe(messageType int, data []byte) error {
	wsWriteMutex.Lock()
	defer wsWriteMutex.Unlock()
	if wsConn == nil {
		return errors.New("connection closed")
	}
	return wsConn.WriteMessage(messageType, data)
}

func writeControlSafe(messageType int, data []byte) error {
	wsWriteMutex.Lock()
	defer wsWriteMutex.Unlock()
	if wsConn == nil {
		return errors.New("connection closed")
	}
	return wsConn.WriteControl(messageType, data, time.Now().Add(5*time.Second))
}

func sendBinarySafe(msg interface{}) error {
	var buf bytes.Buffer
	if err := struc.Pack(&buf, msg); err != nil {
		return err
	}
	return writeSafe(websocket.BinaryMessage, buf.Bytes())
}

func RequestIP(serverUrl string) (string, error) {
	connMutex.Lock()
	defer connMutex.Unlock()

	if wsConn != nil {
		wsConn.Close()
		wsConn = nil
	}
	if stopHeartbeat != nil {
		close(stopHeartbeat)
		stopHeartbeat = nil
	}

	u, err := url.Parse(serverUrl)
	if err != nil {
		return "", err
	}

	dialer := &websocket.Dialer{
		HandshakeTimeout: 15 * time.Second,
	}

	// SSL/TLS Force Bypass for WSS (Required for self-signed certificates in China/1Panel)
	if strings.HasPrefix(strings.ToLower(serverUrl), "wss://") {
		dialer.TLSClientConfig = &tls.Config{InsecureSkipVerify: true}
		logToMobile("TLS: InsecureSkipVerify FORCED (Bypassing 1Panel/Self-Signed check)")
	}

	if proxyUrl != "" {
		pu, err := url.Parse(proxyUrl)
		if err == nil {
			scheme := strings.ToLower(pu.Scheme)
			if scheme == "socks5" || scheme == "socks5h" {
				logToMobile("PROXY: Using SOCKS5 proxy (Remote DNS) -> " + proxyUrl)
				pd, err := proxy.SOCKS5("tcp", pu.Host, nil, proxy.Direct)
				if err == nil {
					dialer.NetDial = pd.Dial
				} else {
					logToMobile("PROXY_ERR: SOCKS5 Dial setup failed: " + err.Error())
				}
			} else {
				dialer.Proxy = http.ProxyURL(pu)
				logToMobile("PROXY: Using HTTP proxy -> " + proxyUrl)
			}
		} else {
			logToMobile("PROXY_ERR: Invalid proxy URL -> " + proxyUrl)
		}
	}
	c, _, err := dialer.Dial(u.String(), nil)
	if err != nil {
		return "", fmt.Errorf("DIAL_ERR: %v", err)
	}

	wsWriteMutex.Lock()
	wsConn = c
	wsWriteMutex.Unlock()

	ts := time.Now().Unix()

	vMsg := &VMacMessage{Type: vmac_kind, VMac: globalVMac, Timestamp: ts}
	vMsg.UpdateHash(password)
	if err := sendBinarySafe(vMsg); err != nil {
		return "", err
	}

	time.Sleep(100 * time.Millisecond)

	eMsg := &ExptTunMessage{Type: dhcp_kind, Timestamp: ts}
	copy(eMsg.Cidr[:], "0.0.0.0/0")
	eMsg.UpdateHash(password)
	if err := sendBinarySafe(eMsg); err != nil {
		return "", err
	}

	_, data, err := wsConn.ReadMessage()
	if err != nil {
		return "", err
	}
	var resp ExptTunMessage
	struc.Unpack(bytes.NewReader(data), &resp)

	// Determine Assigned IP, Subnet Mask Size, and Network Address
	cidrStr := strings.TrimRight(string(resp.Cidr[:]), "\x00")
	if cidrStr == "" {
		return "", errors.New("POOL_EMPTY")
	}

	ip, ipnet, err := net.ParseCIDR(cidrStr)
	if err != nil {
		return "", err
	}

	// Gateway handling (convention .1)
	// FIX v52: Copy IP before modification to avoid corrupting ipnet.IP (Network Address)
	gwIP := make(net.IP, 4)
	copy(gwIP, ipnet.IP.To4())
	gwIP[3] = 1 // Assume gateway is .1

	localIP := binary.BigEndian.Uint32(ip.To4())
	ones, _ := ipnet.Mask.Size()

	// Return full config string: IP|GW|PREFIX|NET
	// gwIP is .1, ipnet.IP is .0 (Uncorrupted now)
	lastConfig = fmt.Sprintf("IP:%s|GW:%s|PREFIX:%d|NET:%s", ip.String(), gwIP.String(), ones, ipnet.IP.String())

	// Authenticate
	aMsg := &AuthMessage{Type: auth, IP: localIP, Timestamp: ts}
	aMsg.UpdateHash(password)
	if err := sendBinarySafe(aMsg); err != nil {
		return "", err
	}

	sendControlPing()
	stopHeartbeat = make(chan struct{})
	go startKeepAliveLoops(localIP, stopHeartbeat)

	return lastConfig, nil
}

func sendControlPing() {
	pingStr := fmt.Sprintf("candy::%s::%s::%s", candyOS, candyVersion, candyHostname)
	writeControlSafe(websocket.PingMessage, []byte(pingStr))
}

func startKeepAliveLoops(localIP uint32, stop chan struct{}) {
	ticker := time.NewTicker(20 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-stop:
			return
		case <-ticker.C:
			if wsConn == nil {
				return
			}
			sendControlPing()
			dMsg := &DiscoveryMessage{Type: discovery, Src: localIP, Dst: 0xFFFFFFFF}
			if err := sendBinarySafe(dMsg); err != nil {
				logToMobile("HEARTBEAT_ERR: " + err.Error())
			}
		}
	}
}

// Simple IP Checksum Calculator
func checksum(data []byte) uint16 {
	sum := uint32(0)
	for i := 0; i < len(data)-1; i += 2 {
		sum += uint32(data[i])<<8 | uint32(data[i+1])
	}
	if len(data)%2 == 1 {
		sum += uint32(data[len(data)-1]) << 8
	}
	for (sum >> 16) > 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	return ^uint16(sum)
}

func fixPacketChecksums(pkt []byte) {
	if len(pkt) < 20 {
		return
	}
	pkt[10] = 0
	pkt[11] = 0
	ipLen := int((pkt[0] & 0x0F) * 4)
	if ipLen > len(pkt) {
		return
	}
	ipSum := checksum(pkt[:ipLen])
	binary.BigEndian.PutUint16(pkt[10:12], ipSum)
	if pkt[9] == 1 {
		icmpOffset := ipLen
		if len(pkt) < icmpOffset+4 {
			return
		}
		pkt[icmpOffset+2] = 0
		pkt[icmpOffset+3] = 0
		icmpSum := checksum(pkt[icmpOffset:])
		binary.BigEndian.PutUint16(pkt[icmpOffset+2:icmpOffset+4], icmpSum)
	}
}

func StartRelayVPN(tunFd int) {
	tunFile := os.NewFile(uintptr(tunFd), "/dev/tun")
	logToMobile("RELAY: Motor v52.3 (Stable).")
	done := make(chan struct{})

	// Tun -> WS
	go func() {
		defer close(done)
		// Optimization: Allocate buffer with 1 extra byte at start for Type
		// Max MTU usually 1500, make buffer safe.
		buf := make([]byte, 2048)

		for {
			// Read packet starting at index 1 to leave space for Type byte
			n, err := tunFile.Read(buf[1:])
			if err != nil {
				break
			}

			// Validate minimal IP packet size
			if n < 20 {
				continue
			}

			// Check Version (IPv4) - buf[1] is the start of IP header
			if (buf[1] >> 4) != 4 {
				continue
			}

			// Checksums (ICMP Fix) - applied to buf[1:] slice
			if buf[1+9] == 1 {
				fixPacketChecksums(buf[1 : 1+n])
			}

			// Prepend Type Byte [0x01] at buf[0]
			buf[0] = forward

			// Send buf[:1+n] (Wait is 1 byte + packet length)
			if err := writeSafe(websocket.BinaryMessage, buf[:1+n]); err != nil {
				logToMobile("RELAY_WRITE_ERR: " + err.Error())
				break
			}
		}
	}()

	// WS -> TUN
	go func() {
		for {
			if wsConn == nil {
				return
			}
			mt, data, err := wsConn.ReadMessage()
			if err != nil {
				logToMobile("WS_READ_ERR: " + err.Error())
				return
			}
			if mt == websocket.BinaryMessage && len(data) > 1 && data[0] == forward {
				// Strip first byte (Type) and verify remaining length
				tunFile.Write(data[1:])
			}
		}
	}()

	<-done
	logToMobile("RELAY: Stopping.")
}
