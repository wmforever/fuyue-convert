# Reviewed Java runtime license fallbacks

These files fill the legal-text gap for runtime JARs that do not embed a
plain-text `LICENSE` or `NOTICE`. They are copied into the generated desktop
license bundle. URLs are immutable release tags; SHA-256 is verified by
`generate-runtime-manifest.mjs` through `runtime-policy.json`.

| File | Version-pinned upstream | SHA-256 |
| --- | --- | --- |
| `BOUNCY-CASTLE-LICENSE.html` | https://raw.githubusercontent.com/bcgit/bc-java/r1rv84/LICENSE.html | `edbbb10380b1271998b867a2e36b1cbee226e03d438726e1a91f80c5dde11849` |
| `TWELVEMONKEYS-LICENSE.txt` | https://raw.githubusercontent.com/haraldk/TwelveMonkeys/twelvemonkeys-3.13.0/LICENSE.txt | `10e1e2688c74b337e0ca9e3a6a02e16319c3ee4cec79b768bca28ab8823ffde2` |
| `CURVESAPI-LICENSE.txt` | https://raw.githubusercontent.com/virtuald/curvesapi/1.08/license.txt | `126424394603617f06ed957255a1f6569ed54f58eec15891ade42405566cc4bc` |
| `DOM4J-LICENSE.txt` | https://raw.githubusercontent.com/dom4j/dom4j/version-2.1.4/LICENSE | `8a447aae670c109596307499775d7946f9a9b875ac787f1927b99eb214021060` |
| `JAXEN-LICENSE.txt` | https://raw.githubusercontent.com/jaxen-xpath/jaxen/v2.0.0/LICENSE.txt | `4659c01f23055af4ed655eae57f227e07bc69b55bb4bebc44f6221079216a7ec` |
| `LATENCYUTILS-LICENSE.txt` | https://raw.githubusercontent.com/LatencyUtils/LatencyUtils/LatencyUtils-2.0.3/LICENSE | `50b43424ea5a8855c49f89e4d3a00fd55fb572ecd0c872f4215f63f654983c6c` |
| `LOGBACK-LICENSE.txt` | https://raw.githubusercontent.com/qos-ch/logback/v_1.5.18/LICENSE.txt | `39a40e634ad63b550cc3e9e20d5a8dd740bed89f22650150b55fc298b9be736e` |
| `EPL-1.0.txt` | https://raw.githubusercontent.com/spdx/license-list-data/v3.27.0/text/EPL-1.0.txt | `f3d8dc494be2e7ea9cd31ac2fb902e35dce7ff8181b9b30d05861b47c339cb25` |

Apache-2.0 fallbacks use the repository root `LICENSE`, whose exact bytes are
also shipped and hashed in the final runtime manifest.
