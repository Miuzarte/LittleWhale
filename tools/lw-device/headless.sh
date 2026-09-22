#!/system/bin/sh
# Run one headless dsh turn on the device, as the app's uid, with the app's own home and tree
#
# usage: headless.sh "<task>" <channel-endpoint> <channel-token> [session-directory]
#
# The point of running it here rather than from the development machine is that everything the
# tools and the model touch is the device's own: the tree the APK shipped, the phone's own
# ~/.credentials.yaml and settings.yaml, and the privileged channel the app is serving. It is how a
# tool is proven to work when a model calls it, rather than when curl calls it
#
# Two limits come from `run-as` and they are easy to mistake for bugs:
# - it gives the app's uid but not the app's mount namespace, so `/storage/emulated/0` - the
#   workspace, which the app itself writes to - is out of reach. The session therefore works in a
#   directory inside the sandbox
# - a file inside an app-private path cannot be read by the file tools even so: something in the
#   read path opens the ancestors of the file, and `/data/user/0` is mode 0711, which the app uid may
#   traverse but not open. Calls that act on the screen are unaffected; reading a picture back has to
#   happen in the app's own host, whose workspace paths are ordinary shared storage

PKG=io.github.miuzarte.littlewhale
FILES=/data/user/0/$PKG/files
CACHE=/data/user/0/$PKG/cache
TASK=$1
ENDPOINT=$2
TOKEN=$3
SESSION_DIR=${4:-$FILES/DSH}

if [ -z "$TASK" ]; then
  echo "usage: headless.sh <task> <endpoint> <token> [session-directory]" >&2
  exit 1
fi
mkdir -p "$CACHE" || exit 1

# The native library directory is renamed by every install, so it is looked up rather than written
# down: the app's own libraries are the last copy of node, bash and ripgrep on the device
APK=$(pm path $PKG | head -1 | sed 's/^package://')
LIB=$(dirname "$APK")/lib/arm64
if [ ! -d "$LIB" ]; then
  echo "no native library directory at $LIB" >&2
  exit 1
fi

export LD_LIBRARY_PATH=$LIB
export OPENSSL_CONF=$FILES/openssl.cnf
export DSH_HOME=$FILES/dsh-home
export HOME=/storage/emulated/0/DSH
export TMPDIR=$CACHE
export SHELL=$LIB/liblwbash.so
export DSH_BASH=$LIB/liblwbash.so
export DSH_RG_PATH=$LIB/liblwrg.so
if [ -n "$ENDPOINT" ]; then
  export LW_CHANNEL_ENDPOINT=$ENDPOINT
  export LW_CHANNEL_TOKEN=$TOKEN
fi

cd "$SESSION_DIR" || exit 1
exec $LIB/libnode.so --expose-internals \
  $FILES/host/node_modules/@deepseek-ai/dsh/lib/bin.js \
  --profile headless --patch $FILES/lw/tool-plugin.yml "$TASK"
