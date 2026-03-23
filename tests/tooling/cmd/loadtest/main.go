package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"net"
	"os"
	"sync"
	"time"

	"brn.local/brnproto/relayproto"
)

type scenario struct {
	NodeID       string `json:"nodeId"`
	RelayToken   string `json:"relayToken"`
	RelayUDP     string `json:"relayUdp"`
	Role         string `json:"role"`
	Packets      int    `json:"packets"`
	PacketBytes  int    `json:"packetBytes"`
}

func main() {
	scenarioFile := flag.String("scenario-file", "load-scenarios.jsonl", "JSONL file of relay sessions to simulate")
	flag.Parse()

	file, err := os.Open(*scenarioFile)
	if err != nil {
		panic(err)
	}
	defer file.Close()

	var scenarios []scenario
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		var entry scenario
		if err := json.Unmarshal(scanner.Bytes(), &entry); err != nil {
			panic(err)
		}
		scenarios = append(scenarios, entry)
	}

	start := time.Now()
	var wg sync.WaitGroup
	for _, item := range scenarios {
		wg.Add(1)
		go func(entry scenario) {
			defer wg.Done()
			runScenario(entry)
		}(item)
	}
	wg.Wait()
	fmt.Printf("completed %d relay flows in %s\n", len(scenarios), time.Since(start))
}

func runScenario(entry scenario) {
	relayAddr, err := net.ResolveUDPAddr("udp", entry.RelayUDP)
	if err != nil {
		fmt.Println("resolve error:", err)
		return
	}
	conn, err := net.DialUDP("udp", nil, relayAddr)
	if err != nil {
		fmt.Println("dial error:", err)
		return
	}
	defer conn.Close()

	hello, err := relayproto.EncodeUDPHello(relayproto.Hello{
		Token:  entry.RelayToken,
		Role:   entry.Role,
		NodeID: entry.NodeID,
	})
	if err != nil {
		fmt.Println("hello error:", err)
		return
	}
	if _, err := conn.Write(hello); err != nil {
		fmt.Println("write hello error:", err)
		return
	}

	payload := make([]byte, entry.PacketBytes)
	for index := 0; index < entry.Packets; index++ {
		if _, err := conn.Write(payload); err != nil {
			fmt.Println("payload error:", err)
			return
		}
	}
}
