import { installErrorHandlers, type ErrorRecord } from './errorReporting'

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * Optional browser telemetry (traces for fetch calls, logs for uncaught errors) sent over OTLP/HTTP
 * to the local OpenTelemetry Collector. Off unless VITE_OTEL_ENABLED=true; see docs/observability.md.
 * The SDK is imported dynamically so a disabled build never loads it.
 */
export async function initTelemetry(): Promise<void> {
  if (import.meta.env.VITE_OTEL_ENABLED !== 'true') return
  const base = (
    import.meta.env.VITE_OTEL_EXPORTER_OTLP_ENDPOINT ?? 'http://localhost:4318'
  ).replace(/\/$/, '')

  try {
    const [
      { resourceFromAttributes },
      { WebTracerProvider, BatchSpanProcessor },
      { LoggerProvider, BatchLogRecordProcessor },
      { OTLPTraceExporter },
      { OTLPLogExporter },
      { FetchInstrumentation },
      { registerInstrumentations },
      { SeverityNumber },
    ] = await Promise.all([
      import('@opentelemetry/resources'),
      import('@opentelemetry/sdk-trace-web'),
      import('@opentelemetry/sdk-logs'),
      import('@opentelemetry/exporter-trace-otlp-http'),
      import('@opentelemetry/exporter-logs-otlp-http'),
      import('@opentelemetry/instrumentation-fetch'),
      import('@opentelemetry/instrumentation'),
      import('@opentelemetry/api-logs'),
    ])

    const resource = resourceFromAttributes({
      'service.name': 'dinoparty-frontend',
      'deployment.environment.name': import.meta.env.MODE,
    })
    const tracerProvider = new WebTracerProvider({
      resource,
      spanProcessors: [new BatchSpanProcessor(new OTLPTraceExporter({ url: `${base}/v1/traces` }))],
    })
    tracerProvider.register()
    const loggerProvider = new LoggerProvider({
      resource,
      processors: [
        new BatchLogRecordProcessor({ exporter: new OTLPLogExporter({ url: `${base}/v1/logs` }) }),
      ],
    })

    // Only URL, method and status are recorded for requests: no headers (Authorization) or bodies.
    registerInstrumentations({
      tracerProvider,
      instrumentations: [
        new FetchInstrumentation({ ignoreUrls: [new RegExp(`^${escapeRegExp(base)}`)] }),
      ],
    })

    const logger = loggerProvider.getLogger('dinoparty-frontend')
    installErrorHandlers(window, (record: ErrorRecord) => {
      logger.emit({
        severityNumber: SeverityNumber.ERROR,
        severityText: 'ERROR',
        body: `${record.type}: ${record.message}`,
        attributes: {
          'event.name': record.kind,
          'exception.type': record.type,
          'exception.message': record.message,
          ...(record.stack ? { 'exception.stacktrace': record.stack } : {}),
          ...(record.source ? { 'code.filepath': record.source } : {}),
        },
      })
    })
    // Flush queued telemetry when the tab is hidden or closed.
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'hidden') {
        void loggerProvider.forceFlush()
        void tracerProvider.forceFlush()
      }
    })
  } catch (error) {
    // Telemetry must never break the app.
    console.warn('Telemetry disabled: failed to initialise', error)
  }
}
