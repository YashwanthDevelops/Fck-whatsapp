#!/usr/bin/env python3
"""Generate the non-secret Info.plist and entitlements consumed by xtool."""

from __future__ import annotations

import argparse
import ipaddress
import os
import plistlib
import re
import tempfile
from pathlib import Path
from typing import Any


IOS_ROOT = Path(__file__).resolve().parent
PROFILE_ROOT = IOS_ROOT / ".build" / "xtool-profile"
PUSHER_APP_IDS = {
    "debug": "dev.friendline.messenger.ios.dev",
    "release": "dev.friendline.messenger.ios",
}
APS_ENVIRONMENTS = {"debug": "development", "release": "production"}


def normalize_matrix_domain(value: str) -> str:
    domain = value.strip().rstrip(".")
    if not domain or any(character in domain for character in "/:@?#"):
        raise ValueError("Use a host name only, without a scheme, port, or path")
    if any(character.isspace() for character in domain):
        raise ValueError("The Matrix domain cannot contain whitespace")

    try:
        ascii_domain = domain.encode("idna").decode("ascii").lower()
    except UnicodeError as error:
        raise ValueError("The Matrix domain is not a valid host name") from error

    try:
        ipaddress.IPv4Address(ascii_domain)
        return ascii_domain
    except ipaddress.AddressValueError:
        pass

    labels = ascii_domain.split(".")
    if len(ascii_domain) > 253 or any(
        not label
        or len(label) > 63
        or re.fullmatch(r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?", label) is None
        for label in labels
    ):
        raise ValueError("The Matrix domain is not a valid host name")
    return ascii_domain


def build_profile_documents(
    base_info: dict[str, Any],
    base_entitlements: dict[str, Any],
    configuration: str,
    push_domain: str | None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    if configuration not in PUSHER_APP_IDS:
        raise ValueError("Configuration must be debug or release")
    if "com.apple.developer.default-data-protection" not in base_entitlements:
        raise ValueError("The base entitlements must retain complete file protection")

    info = dict(base_info)
    entitlements = dict(base_entitlements)
    enabled = push_domain is not None
    info["MatrixPushEnabled"] = enabled
    info["MatrixPushDomain"] = normalize_matrix_domain(push_domain) if enabled else ""
    info["MatrixPushAppID"] = PUSHER_APP_IDS[configuration]

    if enabled:
        entitlements["aps-environment"] = APS_ENVIRONMENTS[configuration]
    else:
        entitlements.pop("aps-environment", None)

    return info, entitlements


def write_plist(path: Path, document: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            plistlib.dump(document, output, sort_keys=True)
        os.replace(temporary_name, path)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)


def generate_profile(configuration: str, push_domain: str | None) -> tuple[Path, Path]:
    with (IOS_ROOT / "Info.plist").open("rb") as source:
        base_info = plistlib.load(source)
    with (IOS_ROOT / "PrivateMessenger-dev.entitlements").open("rb") as source:
        base_entitlements = plistlib.load(source)

    info, entitlements = build_profile_documents(
        base_info,
        base_entitlements,
        configuration,
        push_domain,
    )
    info_path = PROFILE_ROOT / "Info.plist"
    entitlements_path = PROFILE_ROOT / "Entitlements.plist"
    write_plist(info_path, info)
    write_plist(entitlements_path, entitlements)
    return info_path, entitlements_path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--configuration",
        choices=tuple(PUSHER_APP_IDS),
        default="debug",
        help="Select the Matrix pusher app ID and APNs environment (default: debug)",
    )
    parser.add_argument(
        "--push-domain",
        help="Enable push for this Matrix host; omit to generate a push-disabled profile",
    )
    arguments = parser.parse_args()

    try:
        info_path, entitlements_path = generate_profile(arguments.configuration, arguments.push_domain)
    except (OSError, ValueError, plistlib.InvalidFileException) as error:
        parser.error(str(error))

    state = "enabled" if arguments.push_domain is not None else "disabled"
    print(f"Generated {arguments.configuration} xtool profile; push is {state}.")
    print(f"Info.plist: {info_path.relative_to(IOS_ROOT)}")
    print(f"Entitlements: {entitlements_path.relative_to(IOS_ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
