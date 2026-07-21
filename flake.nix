{
  description = "fig-ure: edge-native IoT monitoring and management system for Raspberry Pi 4B";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            jdk21
            clojure
            babashka
            clj-kondo
            cljfmt
            ffmpeg
            cloudflared
            i2c-tools
          ];

          shellHook = ''
            echo "fig-ure dev environment loaded (Clojure, Babashka, clj-kondo, cljfmt, ffmpeg)"
          '';
        };
      }
    );
}
