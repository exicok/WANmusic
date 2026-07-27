# Repository maintenance scripts

`Clean-Repository.ps1` removes generated Android/Gradle output and local IDE files without touching source code.

On Windows, double-click `Clean-Repository.bat` in the project root for a quick cleanup.

BAT options:

```text
Clean-Repository.bat preview   Preview only
Clean-Repository.bat untrack   Clean and untrack generated files
Clean-Repository.bat all       Also remove/untrack local.properties
```

Preview the cleanup:

```powershell
.\scripts\Clean-Repository.ps1 -WhatIf
```

Run the local cleanup:

```powershell
.\scripts\Clean-Repository.ps1
```

Remove previously tracked generated files from the Git index as well:

```powershell
.\scripts\Clean-Repository.ps1 -Untrack
```

`local.properties` is ignored but preserved by default. Remove and untrack it explicitly only when needed:

```powershell
.\scripts\Clean-Repository.ps1 -Untrack -IncludeLocalConfig
```

After using `-Untrack`, review `git status --short` before committing.
