{
  description = "limier: search your mailboxes for lost mail (Android, Rust + Kotlin)";

  inputs = {
    nixpkgs = {
      url = "github:nixos/nixpkgs?tag=25.11&rev=c767db50e209f33ffce3c18165b36101079d367d";
    };
    fenix = {
      url = "github:nix-community/fenix/monthly";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    flake-utils = {
      url = "github:numtide/flake-utils";
    };
    flake-compat = {
      url = "github:edolstra/flake-compat";
      flake = false;
    };
  };

  outputs =
    {
      nixpkgs,
      fenix,
      flake-utils,
      ...
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        # Stable Rust (fenix monthly, as in the rest of the suite) plus
        # the std libs for the four Android ABIs we ship.
        fx = fenix.packages.${system};
        rust = fx.combine [
          fx.stable.toolchain
          fx.targets.aarch64-linux-android.stable.rust-std
          fx.targets.armv7-linux-androideabi.stable.rust-std
          fx.targets.x86_64-linux-android.stable.rust-std
          fx.targets.i686-linux-android.stable.rust-std
        ];

        # Bump these together; the NDK version must exist in the pinned
        # nixpkgs androidenv.
        buildToolsVersion = "34.0.0";
        ndkVersion = "26.3.11579264";

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "34" ];
          buildToolsVersions = [ buildToolsVersion ];
          includeNDK = true;
          ndkVersions = [ ndkVersion ];
          cmakeVersions = [ "3.22.1" ];
          includeEmulator = false;
          includeSystemImages = false;
        };
        androidSdk = androidComposition.androidsdk;
        sdkRoot = "${androidSdk}/libexec/android-sdk";
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = [
            rust
            pkgs.cargo-ndk
            pkgs.jdk17
            pkgs.gradle
            androidSdk
          ];

          ANDROID_HOME = sdkRoot;
          ANDROID_SDK_ROOT = sdkRoot;
          ANDROID_NDK_ROOT = "${sdkRoot}/ndk/${ndkVersion}";
          ANDROID_NDK_HOME = "${sdkRoot}/ndk/${ndkVersion}";
          JAVA_HOME = pkgs.jdk17.home;

          # AGP ships a Maven aapt2 dynamically linked for generic Linux,
          # which cannot run on NixOS. The override must reach the Gradle
          # daemon, so it goes through gradle.properties (GRADLE_OPTS only
          # configures the client JVM). An isolated, gitignored
          # GRADLE_USER_HOME keeps the store-specific path out of the
          # tracked config and out of other projects' ~/.gradle.
          shellHook = ''
            export GRADLE_USER_HOME="$PWD/android/.gradle-home"
            mkdir -p "$GRADLE_USER_HOME"
            echo "android.aapt2FromMavenOverride=${sdkRoot}/build-tools/${buildToolsVersion}/aapt2" \
              > "$GRADLE_USER_HOME/gradle.properties"
          '';
        };
      }
    );
}
