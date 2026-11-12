# On Windows use `mingw32-make` (or run the commands by hand).
TEST_DATABASE_URL ?= postgres://clinic_owner:clinic_owner_dev@localhost:55432/clinic_test?sslmode=disable
export TEST_DATABASE_URL

.PHONY: up down check test vet build reset

up:            ## build and start everything: http://localhost:8080
	docker compose up --build -d
	@echo "open http://localhost:8080"

down:
	docker compose down

reset:         ## wipe the database volume and reseed
	docker compose down -v
	docker compose up --build -d

vet:
	go vet ./...

build:
	go build ./...

# -p 1: the integration tests share one database and reset it per test.
test:
	REQUIRE_DB=1 go test -p 1 -count=1 ./...

check: vet build test   ## the fitness functions (needs `docker compose up -d postgres`)
