# Immich API-key permissions

Create a dedicated display-only API key in the Immich web interface from the
user settings panel. Give it only the permissions required by the sources you
actually enable.

## Minimum tested permission set

The verified starter profile in this repository uses these read-only
permissions:

| Permission | Why this project needs it |
| --- | --- |
| `asset.download` | Kiosk fetches the original asset on the Docker host before resizing it for the frame. |
| `asset.read` | Reads asset records and metadata needed to build slides. |
| `asset.view` | Allows the display user to view the selected assets. |
| `archive.read` | Allows Kiosk to respect archive visibility settings such as `show_archived: false`. |
| `memory.read` | Required for the `memories` source in the starter profile. |
| `user.read` | Required by the tested Kiosk session for the display user context. |

## Enable these only if you use the matching source type

| Source type or feature | Additional permission |
| --- | --- |
| `album` sources | `album.read`, `album.statistics` |
| `person` sources | `face.read`, `person.read` |
| `tag` sources | `tag.read` |

If you do not use a source type, leave its permissions off.

## Permissions you should not grant

Do not grant write, edit, archive, delete, upload, or admin permissions to the
frame key. This project is designed around a display-only identity.

## Practical checks

After creating the key:

1. Store it only in `secrets/immich_api_key`.
2. Confirm the slideshow loads.
3. Test any optional profile that uses albums, people, tags, or memories.
4. If a profile fails, add only the missing read permission that corresponds to
   that source type.
