#!/bin/bash

# Copyright (c) guangzhou xiaojudeng 2016-2022, wjun_java@163.com.
# Licensed under the GNU Lesser General Public License (LGPL) ,Version 3.0 (the "License");
# You may obtain a copy of the License at
# http://www.gnu.org/licenses/lgpl-3.0.txt
# http://www.xjd2020.com
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Deploy plugin jars to the local fastcms runtime directory.
#
# Usage:
#   ./deploy-plugins.sh              build all plugins, then copy jars
#   ./deploy-plugins.sh --no-build   copy existing target jars only (no build)
#
# Target directory:
#   $FASTCMS_HOME/plugins  if FASTCMS_HOME is set
#   ~/fastcms/plugins      otherwise (same as web runtime default)
#
# NOTE: stop the web app before deploying, a running JVM may lock the jars.
# After deploy, restart the web app to take effect.

set -u

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

error_exit ()
{
    echo "ERROR: $1 !!"
    exit 1
}

if [ -n "${FASTCMS_HOME:-}" ]; then
    PLUGIN_DIR="$FASTCMS_HOME/plugins"
else
    PLUGIN_DIR="$HOME/fastcms/plugins"
fi

echo "Target plugin dir: $PLUGIN_DIR"

SKIP_BUILD=0
if [ "${1:-}" = "--no-build" ]; then
    SKIP_BUILD=1
fi

if [ "$SKIP_BUILD" -eq 0 ]; then
    echo "[1/2] Building plugins ..."
    mvn -f "$SCRIPT_DIR/plugins/pom.xml" clean package -DskipTests || error_exit "Build FAILED, abort."
else
    echo "[1/2] Skip build (--no-build)"
fi

echo "[2/2] Copying plugin jars ..."
mkdir -p "$PLUGIN_DIR"

COPIED=0
for p in "$SCRIPT_DIR"/plugins/*-plugin; do
    [ -d "$p" ] || continue
    jar_list=$(ls "$p"/target/*.jar 2>/dev/null)
    [ -z "$jar_list" ] && continue
    name=$(basename "$p")
    if cp $jar_list "$PLUGIN_DIR/" 2>/dev/null; then
        echo "  copied: $name"
        COPIED=$((COPIED + 1))
    else
        echo "  COPY FAILED: $name (jar locked? stop web app first)"
    fi
done

echo "Done. $COPIED plugin jar(s) copied to $PLUGIN_DIR"
echo "Restart the web app to take effect."
