{ config, lib, pkgs, ... }:

let
  cfg = config.programs.metuxa;
in
{
  options.programs.metuxa = {
    enable = lib.mkEnableOption "metuxa — stream a Wayland session to a Meta Quest 3";

    package = lib.mkOption {
      type = lib.types.package;
      default = pkgs.callPackage ./package.nix { };
      defaultText = lib.literalExpression "pkgs.callPackage ./package.nix { }";
      description = "The metuxa package to install (streamer + GTK4 control panel).";
    };

    autostart = lib.mkOption {
      type = lib.types.bool;
      default = false;
      description = ''
        Launch the metuxa control panel automatically on login by
        installing an XDG autostart entry.
      '';
    };
  };

  config = lib.mkIf cfg.enable {
    home.packages = [ cfg.package ];

    # Start the GTK4 control panel on login when requested.
    xdg.configFile."autostart/metuxa-control.desktop" = lib.mkIf cfg.autostart {
      text = ''
        [Desktop Entry]
        Type=Application
        Name=metuxa
        Comment=Stream a Wayland session to a Meta Quest 3 floating window
        Exec=${lib.getExe cfg.package}
        Terminal=false
        X-GNOME-Autostart-enabled=true
      '';
    };
  };
}
