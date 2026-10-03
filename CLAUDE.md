# Working in this repo

## Commit messages
- Warn the user if the current branch is 'main'. Only continue on confirmation if it is.
- Before committing, prepare a line to be added to the changelog and confirm with the user if it should be added. If confirmed, add it to the "Next Release" section in the file
- Description body: at most 10 lines.
- Do not add a "Co-Authored-By: Claude" (or similar) trailer.
- Do not push changes automatically

## Documentation and code comments
- Keep the style terse as this is technical docomentation. Focus on facts not history.
- Make sure to remove self-references (e.g. "in a previous version we wrote").
- Don't make references to private repositories.
- Mark assumptions clearly as such.