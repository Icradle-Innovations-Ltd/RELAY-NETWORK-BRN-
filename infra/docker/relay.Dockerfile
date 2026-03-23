FROM golang:1.25.3-bookworm AS builder

WORKDIR /src
COPY go.work ./
COPY packages/go/brnproto ./packages/go/brnproto
COPY services/relay ./services/relay

WORKDIR /src/services/relay
RUN go build -o /out/brn-relay .

FROM debian:bookworm-slim
RUN useradd -r -s /usr/sbin/nologin brn
COPY --from=builder /out/brn-relay /usr/local/bin/brn-relay
USER brn
EXPOSE 51820/udp 8443/tcp 9090/tcp
CMD ["/usr/local/bin/brn-relay"]
