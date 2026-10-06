# Fixture: fail (mcp-tool-docs)

FILL: miniature A/B trees for this fixture.

- **pass/**: every A definition appears at B (script must exit 0).
- **fail/**: one synthetic offender at A with no B reference and no
  escape-hatch marker (script must exit non-zero and name it).
- **exempt/**: offender carrying the escape-hatch marker
  (script must exit 0 and list it as exempt).

Point the script's env overrides at these dirs to run, e.g.:

    A_FILE=$PWD/pass/a.txt B_DIR=$PWD/pass ./scripts/checks/mcp-tool-docs.sh
