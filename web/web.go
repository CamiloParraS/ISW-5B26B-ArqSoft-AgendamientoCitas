// Package web embeds the demo console (vanilla HTML/JS, no build step).
package web

import (
	"embed"
	"net/http"
)

//go:embed index.html app.js styles.css
var files embed.FS

func Handler() http.Handler {
	fs := http.FileServerFS(files)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-cache")
		fs.ServeHTTP(w, r)
	})
}
