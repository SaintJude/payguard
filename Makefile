# Homebrew's unversioned `openjdk` formula tracks the latest release (26 as
# of writing); Lombok's annotation processor doesn't yet support that javac,
# so it silently fails to generate getters/setters/constructors and every
# build breaks. Pin to the LTS this project actually targets (see CLAUDE.md).
export JAVA_HOME := /opt/homebrew/opt/openjdk@21

.PHONY: test run-api run-worker start stop verify clean

test:
	mvn -f services/payment-api/pom.xml test
	mvn -f services/worker/pom.xml test

run-api:
	mvn -f services/payment-api/pom.xml spring-boot:run

run-worker:
	mvn -f services/worker/pom.xml spring-boot:run

# Builds both services and runs them as background processes (direct JVM
# execution — no containers yet, that's Phase 3). PIDs/logs land in .run/,
# which is gitignored.
start:
	mvn -f services/payment-api/pom.xml -q package -DskipTests
	mvn -f services/worker/pom.xml -q package -DskipTests
	mkdir -p .run
	nohup $(JAVA_HOME)/bin/java -jar services/payment-api/target/payment-api-0.1.0-SNAPSHOT.jar > .run/payment-api.log 2>&1 & echo $$! > .run/payment-api.pid
	sleep 3
	nohup $(JAVA_HOME)/bin/java -jar services/worker/target/worker-0.1.0-SNAPSHOT.jar > .run/worker.log 2>&1 & echo $$! > .run/worker.pid
	sleep 3
	@echo "Started. Logs: .run/payment-api.log .run/worker.log"

stop:
	-kill $$(cat .run/payment-api.pid 2>/dev/null) 2>/dev/null || true
	-kill $$(cat .run/worker.pid 2>/dev/null) 2>/dev/null || true
	rm -f .run/*.pid

verify: start
	./scripts/verify.sh; STATUS=$$?; $(MAKE) stop; exit $$STATUS

clean:
	mvn -f services/payment-api/pom.xml clean
	mvn -f services/worker/pom.xml clean
