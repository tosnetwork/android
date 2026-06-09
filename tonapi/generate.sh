#!/bin/bash

OPENAPI_URL="$1"
PACKAGE_NAME="io.$2"
TARGET_SRC_DIR='src/main/kotlin'
TEMP_DIR='temp_api_gen'
TEMPLATE_DIR='./templates'

rm -rf "$TEMP_DIR"
mkdir -p "$TEMP_DIR"

openapi-generator generate \
  -i "$OPENAPI_URL" \
  -g kotlin \
  -o "$TEMP_DIR" \
  -t "$TEMPLATE_DIR" \
  --library jvm-okhttp4 \
  --global-property apiTests=false,modelTests=false \
  --type-mappings "number=kotlin.String" \
  --additional-properties "serializationLibrary=kotlinx_serialization,packageName=$PACKAGE_NAME,platform=android,hideGenerationTimestamp=true,enumUnknownDefaultCase=true,enumUnknownDefaultCaseName=UNKNOWN"

if [ $? -ne 0 ]; then
    echo "Failed to generate API client."
    rm -rf "$TEMP_DIR"
    exit 1
fi

find "$TEMP_DIR" -type f -name "*.kt" -print0 | xargs -0 sed -i '' 's/kotlin.collections.Map<kotlin.String, kotlin.Any>/kotlin.collections.Map<kotlin.String, io.JsonAny>/g'
find "$TEMP_DIR" -type f -name "*.kt" -print0 | xargs -0 sed -i '' 's/@SerialName(value = "unknown_default_open_api")/@SerialName(value = "unknown")/g'
find "$TEMP_DIR" -type f -name "*.kt" -print0 | xargs -0 sed -i '' 's/unknown_default_open_api("unknown_default_open_api")/unknown("unknown")/g'
find "$TEMP_DIR" -type f -name "*.kt" -print0 | xargs -0 sed -i '' 's/.unknown_default_open_api/.unknown/g'
find "$TEMP_DIR" -type f -name "*.kt" -print0 | xargs -0 sed -i '' 's/values()/entries/g'

GENERATED_SRC_PATH="$TEMP_DIR/src/main/kotlin/"
if [ ! -d "$GENERATED_SRC_PATH" ]; then
    echo "Failed to find generated source directory: $GENERATED_SRC_PATH"
    rm -rf "$TEMP_DIR"
    exit 1
fi
mkdir -p "$TARGET_SRC_DIR"
cp -r "$GENERATED_SRC_PATH"* "$TARGET_SRC_DIR/"
if [ $? -ne 0 ]; then
    echo "Failed to copy generated sources to target directory: $TARGET_SRC_DIR"
    rm -rf "$TEMP_DIR"
    exit 1
fi

INFRA_DIR="$TARGET_SRC_DIR/io/$2/infrastructure"
if [ -d "$INFRA_DIR" ]; then
    rm -rf "$INFRA_DIR"
else
    echo "Failed to find infrastructure directory: $INFRA_DIR"
fi

rm -rf "$TEMP_DIR"

echo "Done"

