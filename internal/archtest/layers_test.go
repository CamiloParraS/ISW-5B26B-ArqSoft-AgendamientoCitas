// Package archtest holds the layer fitness function (ADR-001). The Go compiler
// already stops a module from importing another module's internal packages and
// rejects import cycles; this test covers the rules inside each module.
package archtest

import (
	"bufio"
	"bytes"
	"os/exec"
	"strings"
	"testing"
)

var modules = []string{"identity", "scheduling", "clinical", "notifications", "audit"}

var drivers = []string{"net/http", "github.com/jackc/pgx"}

// forbidden lists, per layer, the import prefixes that layer must not use.
// "{m}" is the module's own path.
var forbidden = map[string][]string{
	"domain":         append([]string{"clinic/{m}/internal/application", "clinic/{m}/internal/infrastructure", "clinic/{m}/internal/presentation"}, drivers...),
	"application":    append([]string{"clinic/{m}/internal/infrastructure", "clinic/{m}/internal/presentation"}, drivers...),
	"presentation":   {"clinic/{m}/internal/infrastructure", "clinic/{m}/internal/domain", "github.com/jackc/pgx"},
	"infrastructure": {"clinic/{m}/internal/application", "clinic/{m}/internal/presentation"},
}

func TestLayerDependencies(t *testing.T) {
	out, err := exec.Command("go", "list", "-f", `{{.ImportPath}}{{range .Imports}} {{.}}{{end}}`, "clinic/...").Output()
	if err != nil {
		t.Fatalf("go list: %v", err)
	}
	checked := 0
	sc := bufio.NewScanner(bytes.NewReader(out))
	for sc.Scan() {
		f := strings.Fields(sc.Text())
		if len(f) == 0 {
			continue
		}
		pkg, imports := f[0], f[1:]
		for _, m := range modules {
			for layer, rules := range forbidden {
				if !strings.HasPrefix(pkg, "clinic/"+m+"/internal/"+layer) {
					continue
				}
				checked++
				for _, imp := range imports {
					for _, rule := range rules {
						if strings.HasPrefix(imp, strings.ReplaceAll(rule, "{m}", m)) {
							t.Errorf("%s must not import %s", pkg, imp)
						}
					}
				}
			}
		}
		// The shared kernel knows nothing about the modules.
		if strings.HasPrefix(pkg, "clinic/internal/platform") {
			for _, imp := range imports {
				for _, m := range modules {
					if strings.HasPrefix(imp, "clinic/"+m) {
						t.Errorf("%s must not import module %s", pkg, imp)
					}
				}
			}
		}
	}
	if want := len(modules) * len(forbidden); checked < want {
		t.Fatalf("checked %d layer packages, expected at least %d: did the layout change?", checked, want)
	}
}
