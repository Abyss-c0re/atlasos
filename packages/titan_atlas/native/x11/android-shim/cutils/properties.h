#pragma once
/* The NDK has no cutils/properties.h. Mesa uses property_get only when
 * getenv() is empty. This server is started with a real environment. */
#include <string.h>

#define PROPERTY_KEY_MAX 32
#define PROPERTY_VALUE_MAX 92

static inline int property_get(const char *key, char *value, const char *default_value)
{
    (void)key;
    if (!value)
        return 0;
    if (!default_value) {
        value[0] = '\0';
        return 0;
    }
    size_t n = strlen(default_value);
    if (n >= PROPERTY_VALUE_MAX)
        n = PROPERTY_VALUE_MAX - 1;
    memcpy(value, default_value, n);
    value[n] = '\0';
    return (int)n;
}
