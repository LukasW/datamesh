---
name: ship
description: Abschliessen — PR mergen, Issue schliessen, aufräumen
disable-model-invocation: true
---

Schliesse die aktuelle Arbeit ab (nach `/implement`):

## MCP-Server ermitteln

Führe zuerst `git remote get-url origin` aus und wähle den passenden MCP-Server:
- URL enthält `github.com` → **GitHub MCP**
- URL enthält `gitlab.com` oder selbst-gehostetes GitLab → **GitLab MCP**
- Andere URL (z.B. eigene Gitea-Instanz) → **Gitea MCP**

Owner und Repo immer aus der Remote-URL ableiten, NICHT hardcoden.

## Schritte

1. **Branch ermitteln**:
   - Aktuellen Branch-Namen merken: `git branch --show-current`

2. **Push sicherstellen**:
   - `git status` prüfen — falls uncommittete Änderungen vorhanden: abbrechen und User fragen
   - `git push` (falls noch nicht geschehen)

3. **DoD und CI prüfen**:
   - Stelle sicher, dass die Definition of Done aus `/implement` erfüllt ist (Hexagonal-Isolation, ODC-Contracts, Tests, Data-Product-Artefakte, kein toter Code)
   - CI-Status des PR prüfen via MCP — **warte bis alle Checks grün sind** (Build, Unit-Tests, Integration-Tests mit Testcontainers, Playwright, Contract-Verifier, Soda-Checks falls vorhanden)
   - Falls ein Check fehlschlägt: abbrechen, User informieren, nicht mergen

4. **PR mergen**:
   - PR zum aktuellen Branch via dem ermittelten MCP-Server finden
   - **Squash merge** — ein sauberer Commit pro Feature/Task auf `main`

5. **Issue schliessen**:
   - Issue-Nummer aus Branch-Name extrahieren (Pattern: `feature/<NUMMER>-...` oder `hotfix/<NUMMER>-...`)
   - Falls nicht automatisch durch `Closes #N` geschlossen: Issue via MCP auf `closed` setzen

6. **Lokal aufräumen**:
   - Prüfe ob wir in einem Worktree sind: `git rev-parse --show-toplevel` vs. Haupt-Repository
   - Falls Worktree:
     - Zum Repository-Root wechseln: `cd $(git worktree list --porcelain | head -1 | cut -d' ' -f2)`
     - `git worktree remove <worktree-pfad>`
     - `git branch -D <branch-name>`
     - `git worktree prune`
   - Falls kein Worktree:
     - `git checkout main && git pull`
     - `git branch -d <branch-name>`

7. **Bestätigung**:
   - Issue-Nummer, PR-Nummer und Merge-Status ausgeben
   - Bestätigen, dass Branch(es) gelöscht wurden
