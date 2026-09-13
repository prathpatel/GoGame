.PHONY: build test test-integration lint vet run-api run-worker docker dev-up dev-down

VERSION ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo dev)
DEV_DATABASE_URL ?= postgres://gogame:gogame@localhost:5432/gogame?sslmode=disable
DEV_REDIS_URL ?= redis://localhost:6379/0

build:
	go build -trimpath -ldflags "-X main.version=$(VERSION)" ./...

vet:
	go vet ./...

lint:
	gofmt -l . | tee /dev/stderr | (! read)

test:
	go test -race ./...

test-integration:
	TEST_DATABASE_URL="$(DEV_DATABASE_URL)" TEST_REDIS_URL="$(DEV_REDIS_URL)" go test -race ./...

docker:
	docker build --build-arg VERSION=$(VERSION) -t gogame-backend:$(VERSION) .

run-api:
	DATABASE_URL="$(DEV_DATABASE_URL)" REDIS_URL="$(DEV_REDIS_URL)" go run ./cmd/api

run-worker:
	DATABASE_URL="$(DEV_DATABASE_URL)" REDIS_URL="$(DEV_REDIS_URL)" go run ./cmd/worker

dev-up:
	docker compose -f deploy/docker-compose.yml up -d

dev-down:
	docker compose -f deploy/docker-compose.yml down -v
