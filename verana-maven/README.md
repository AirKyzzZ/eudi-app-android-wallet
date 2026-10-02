# verana-maven

EUDI Wallet Core `0.30.2` rebuilt against `eudi-lib-jvm-openid4vci-kt` `0.14.0`, the first release that accepts JWT proofs without key attestation (openid4vci-kt #602). The Verana devnet issuers advertise plain `jwt` proofs, which every Wallet Core release up to `0.30.2` (built on openid4vci-kt `0.13.1`) rejects.

Source: https://github.com/AirKyzzZ/eudi-lib-android-wallet-core, tag `0.30.2-verana.2`. Rebuild with `./gradlew publishToMavenLocal -PRELEASE_SIGNING_ENABLED=false` and copy the three `0.30.2-verana.2` directories from `~/.m2/repository/eu/europa/ec/eudi/` here.

Drop this directory once an upstream Wallet Core release ships openid4vci-kt `0.14.0` or later.
