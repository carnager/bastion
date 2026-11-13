# Signing release builds

No keys or passwords live in this repository. `app/build.gradle.kts` reads
the signing values at build time from, in order of precedence:

1. environment variables of the same name, then
2. the properties file named by `BASTION_ANDROID_SIGNING_PROPERTIES`, or
   by default `~/.local/android/release-keys/bastion.properties`.

| Key                              | Meaning                    |
|----------------------------------|----------------------------|
| `BASTION_ANDROID_STORE_FILE`     | path to the keystore       |
| `BASTION_ANDROID_STORE_PASSWORD` | keystore password          |
| `BASTION_ANDROID_KEY_ALIAS`      | key alias (`bastion`)      |
| `BASTION_ANDROID_KEY_PASSWORD`   | key password               |

Without them, `assembleRelease` signs with the local debug key.

## The maintainer's setup

The key is alias `bastion` (RSA 3072, valid 10000 days) in the shared
`~/.local/android/release-keys/release.keystore`. Its certificate's SHA-256 is

    EF:1F:7A:2F:81:69:46:56:B4:C9:BD:24:68:87:62:A8:83:35:8C:A3:49:49:B8:E1:63:14:CD:51:5E:A8:29:0A

which `apksigner verify --print-certs` must show for published APKs.
