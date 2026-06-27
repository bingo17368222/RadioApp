#ifndef GGML_H
#define GGML_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

// Minimal stub for ggml.h - only defines types used by whisper.h

enum ggml_log_level {
    GGML_LOG_LEVEL_NONE  = 0,
    GGML_LOG_LEVEL_INFO  = 1,
    GGML_LOG_LEVEL_WARN  = 2,
    GGML_LOG_LEVEL_ERROR = 3,
    GGML_LOG_LEVEL_DEBUG = 4,
    GGML_LOG_LEVEL_CONT  = 5,
};

typedef void (*ggml_log_callback)(enum ggml_log_level level, const char * text, void * user_data);
typedef bool (*ggml_abort_callback)(void * data);

#endif // GGML_H
