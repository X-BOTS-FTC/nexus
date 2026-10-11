{
  description = "nexus development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

    flake-utils.url = "github:numtide/flake-utils";

    # android-nixpkgs = {
    #   url = "github:tadfisher/android-nixpkgs/stable";
    #   inputs.nixpkgs.follows = "nixpkgs";
    # };
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
      in {
        devShells.default = pkgs.mkShell {
          packages = [ pkgs.jdk17 ];

          JAVA_HOME = pkgs.jdk17.home;

          shellHook = ''
            echo "nexus dev shell (java $(java -version 2>&1 | head -n1))"
          '';
        };
      });
}
