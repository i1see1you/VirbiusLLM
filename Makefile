.PHONY: build test smoke agent core init-db

init-db:
	bash scripts/init-databases.sh

build:
	mvn -pl virbius-engine,virbius-control,virbius-compiler -am package -DskipTests
	cd virbius-gateway-agent && cargo build --release
	mkdir -p target && cp virbius-gateway-agent/target/release/virbius-gateway-agent target/

agent:
	cd virbius-gateway-agent && cargo build --release
	mkdir -p target && cp virbius-gateway-agent/target/release/virbius-gateway-agent target/

core:
	cd virbius-core && cargo build --release

smoke: build
	bash scripts/dev-up.sh
	sleep 2
	bash scripts/smoke-test.sh

test: build
	mvn test
