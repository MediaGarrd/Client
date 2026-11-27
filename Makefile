.PHONY: test

test:
	./gradlew test --warning-mode all
	node --test src/test/js/*.test.mjs

