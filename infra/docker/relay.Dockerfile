FROM golang:1.25.3-bookworm AS builder

WORKDIR /src
COPY packages/go/brnproto ./packages/go/brnproto
COPY services/relay ./services/relay

# Create a minimal go.work with only the modules needed for the relay
RUN printf 'go 1.25.3\n\nuse (\n\t./packages/go/brnproto\n\t./services/relay\n)\n' > go.work

WORKDIR /src/services/relay
RUN go build -o /out/brn-relay .

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*
RUN useradd -r -s /usr/sbin/nologin brn
COPY --from=builder /out/brn-relay /usr/local/bin/brn-relay
USER brn
EXPOSE 51820/udp 8443/tcp 9090/tcp
CMD ["/usr/local/bin/brn-relay"]
