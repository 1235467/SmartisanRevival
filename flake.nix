{
  description = "SmartisanRevival Android debug build";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config.allowUnfree = true;
        config.android_sdk.accept_license = true;
      };
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "35" ];
        buildToolsVersions = [ "35.0.0" ];
        includeNDK = false;
        includeEmulator = false;
        includeSystemImages = false;
        includeSources = false;
      };
      sdk = android.androidsdk;
    in {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          pkgs.jdk17
          pkgs.gradle_8
          pkgs.unzip
          pkgs.zip
          pkgs.curl
          pkgs.cacert
          pkgs.git
          pkgs.which
        ];
        JAVA_HOME = "${pkgs.jdk17.home}";
        ANDROID_HOME = "${sdk}/libexec/android-sdk";
        ANDROID_SDK_ROOT = "${sdk}/libexec/android-sdk";
      };
    };
}
