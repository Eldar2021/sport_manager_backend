git-update:
	echo "Updating git..."
	git pull
	git branch | grep -v "develop" | xargs git branch -D
	git remote prune origin
