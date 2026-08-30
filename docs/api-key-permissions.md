# Immich API-key permissions

Create a dedicated display-only API key in the Immich web interface from the
user settings panel. Give it only the permissions required by the sources you
actually enable. Immich and Kiosk permissions can change between releases, so
compare this tested set with the
[current upstream Kiosk permission list](https://docs.immichkiosk.app/installation/#api-key-permissions)
when upgrading either service.

## Minimum tested permission set

The verified starter profile in this repository uses these read-only
permissions:

| Permission | Why this project needs it |
| --- | --- |
| `asset.read` | Reads asset records and metadata needed to build slides. |
| `asset.view` | Allows the display user to view the selected assets. |
| `archive.read` | Allows Kiosk to respect archive visibility settings such as `show_archived: false`. |
| `memory.read` | Required for the `memories` source in the starter profile. |
| `user.read` | Required by the tested Kiosk session for the display user context. |

The tested Immich 3.0.3 deployment also required `asset.download` when
`use_original_image: true` fetched the original before NAS-side resizing. Keep
that permission enabled when it appears in your Immich version.

## Enable these only if you use the matching source type

| Source type or feature | Additional permission |
| --- | --- |
| `album` sources | `album.read`, `album.statistics` |
| Album-name labels, exclusions, or penalties | `album.read` |
| `person` sources | `face.read`, `person.read`, `person.statistics` |
| `tag` sources | `tag.read` |
| Kiosk `/about` page | `server.about` |

If you do not use a source type, leave its permissions off.

## Permissions you should not grant

Do not grant write, edit, archive, delete, upload, or admin permissions to the
frame key. In particular, the included configuration disables Kiosk's
like/hide controls and therefore does not need asset, album, or tag write
permissions. This project is designed around a display-only identity.

## Practical checks

After creating the key:

1. Store it only in `secrets/immich_api_key`.
2. Confirm the slideshow loads.
3. Test any optional profile that uses albums, people, tags, or memories.
4. If a profile fails, add only the missing read permission that corresponds to
   that source type.
