.PHONY: compile release test_unit test_brand_boundary test_v1_static test_v1_emulator test_v1_emulator_matrix test_v1_localnet test_release_artifacts test_all test_v1_acceptance

compile:
	./gradlew :apps:wallet:instance:main:assembleDefaultDebug :apps:signer:assembleDebug

release:
	./gradlew :apps:wallet:instance:main:assembleSiteRelease :apps:signer:assembleRelease

test_unit:
	./gradlew testDebugUnitTest

test_brand_boundary:
	bash scripts/test_brand_boundary.sh

test_v1_static:
	bash scripts/test_v1_static.sh

test_v1_emulator:
	bash scripts/test_v1_emulator.sh

test_v1_emulator_matrix:
	bash scripts/test_v1_emulator_matrix.sh

test_v1_localnet:
	bash scripts/test_v1_localnet.sh

test_release_artifacts: release
	bash scripts/test_release_artifacts.sh

test_all: test_brand_boundary test_v1_static test_unit

test_v1_acceptance: test_all compile test_v1_localnet test_v1_emulator test_v1_emulator_matrix test_release_artifacts
