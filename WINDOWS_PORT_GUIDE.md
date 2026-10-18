# Contributing the Windows port

Hi! Thanks for working on Silage. Your code goes on the existing **`windows-port`** branch of [evman/silage](https://github.com/evman/silage). You have been invited to this repository with write access, so you can push to that branch directly.

These commands are for **PowerShell** on Windows. Git and GitHub CLI (`gh`) should already be installed.

## 1. Accept the invitation

Sign in to GitHub as **minimalhandle** and accept the invitation to collaborate on `evman/silage`. Look for the invitation in your GitHub notifications or the email GitHub sent you. You must accept it before you can push code.

## 2. Sign in from PowerShell

```powershell
gh auth login --web --git-protocol https
gh auth setup-git
gh auth status
```

Follow the browser prompts and sign in as **minimalhandle**. The final command should show that account as logged in. If you already signed in with `gh`, you can skip the first command, but check `gh auth status`.

## 3. Download the Windows branch

In PowerShell, go to the folder where you want the project, then run:

```powershell
git clone --branch windows-port https://github.com/evman/silage.git
cd silage
git status
```

`git status` should say you are on branch `windows-port`. Make your code changes in this `silage` folder. You do not need to make a fork or a new branch.

## 4. Save and send your changes

When a set of changes is ready:

```powershell
git status
git add -A
git commit -m "Add Windows support"
git push origin windows-port
```

The commit message can describe the specific work you did. You can repeat these steps as you make progress; you do not need to finish the whole port in one commit.

If Git asks you to set your identity before committing, run these once, using your own name and email, then retry the commit:

```powershell
git config --global user.name "Your Name"
git config --global user.email "your-github-email@example.com"
```

To get new changes from the branch before your next work session:

```powershell
git pull origin windows-port
```

When you push, send me a Discord message with a short summary of what works, what still needs work, and any build or test results. Your commits will appear on [the Windows branch](https://github.com/evman/silage/tree/windows-port).

## If something goes wrong

- **`Permission denied` or HTTP 403 on push:** Confirm you accepted the invitation and that `gh auth status` shows **minimalhandle**. Run `gh auth setup-git` again if needed.
- **`git push` says the remote has new work:** Run `git pull origin windows-port`, resolve any reported conflicts, then push again. Ask me if Git reports a conflict and you are unsure what to keep.
- **Not sure which branch you are on:** Run `git branch --show-current`. It should print `windows-port`. Stop and ask me before pushing if it says something else.

Please don't put passwords, tokens, or your Silo server credentials in commits or screenshots.
