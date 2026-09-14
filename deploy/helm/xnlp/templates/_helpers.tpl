{{- define "xnlp.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- define "xnlp.fullname" -}}
{{- if .Values.fullnameOverride }}{{ .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}{{ else }}{{ include "xnlp.name" . }}{{ end }}
{{- end }}
{{- define "xnlp.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ include "xnlp.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}
{{- define "xnlp.selectorLabels" -}}
app.kubernetes.io/name: {{ include "xnlp.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
{{- define "xnlp.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}{{ default (include "xnlp.fullname" .) .Values.serviceAccount.name }}{{ else }}{{ default "default" .Values.serviceAccount.name }}{{ end }}
{{- end }}
