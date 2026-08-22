# AtlasOS algocubes (salvage from heretic session 019ff9d1)

Cube law: energy must flow. These units are ROM-worthy. The rest is heresy.
HOLD FLASH. Next GSI pin only. Never resume 019ff9d1 to fix live.

## Keep (algocubes)

| Cube | SoT | Landed |
|------|-----|--------|
| A1 incoming-binder both ImsPhones | 0210 default block=false; 0211 0212 product.prop; 0080 Misc default ON | atlasos f5f5cdd + titanus2 9c0d04c2 SERIES |
| A2 Settings Calls is voice SoT | multi_sim_voice_call is subId; map _id to sim_id+1 for vendor simswitch; never write Calls | cc2b223 early.sh |
| A3 persist is cache | titan2_simswitch boot-early only; hold re-reads Settings | a389b9e |
| A4 never xsim | PhoneCalls always setCrossSimCallingEnabled false | 79c1e5f |
| A5 pulled card is gone | slot less than 0 / INVALID / ABSENT not listed; 0092a | 951bf31 |
| A6 bind 1 or 2 or both | both must skip ABSENT trays | ImsCalls + setup |
| A7 pad ROM outranks KEEP_DATA tip | system bin apply not data local tmp leftover | d1d32b7 |
| A8 no ImsService inject | DANGEROUS_IMS_INJECT bootloop | banned |
| A9 Treble BT seed-once | heal does not restamp BT persist | heal law |
| A10 WFC cellular-preferred | WIFI_PREFERRED starved LTE | setup wfc mode 1 |

## Heresy (do not recycle)

- Overwrite multi_sim_voice_call
- first-LOADED fallback when dumpsys hangs
- Bind one tray then the other as a fix
- Delete siminfo row and call it ROM
- Data overlay as if system priv-app is fixed
- Airplane pulse to kick LTE
- Invent felt MO or MT
- Compact amnesia as a work method
- Stock ImsService or re-sign android.uid.phone
- Restamp BT persist every heal tick

## Energy

Vendor simswitch is 1-indexed tray. Settings Calls is subscription id.
calls_want maps subId to slot+1. Do not use raw subId 3 as tray 3.
Default bind-both must not poke an ABSENT ImsPhone.
