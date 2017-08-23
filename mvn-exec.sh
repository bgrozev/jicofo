#!/bin/sh -e

function error_exit
{
	echo "$1" 1>&2
	exit 1
}

function exec_jvb
{
  # Run profiles
  #XMPP_DOMAIN="jitsi-meet.lab.jitsi"
  #XMPP_SECRET="jFR8PcZV"
  #XMPP_PORT="5347"
  XMPP_DOMAIN="jinja.jitsi.net"
  XMPP_SECRET="AxOHMJ28"

  XMPP_PORT="5347"

  exec mvn -f $POM exec:java \
    -Djna.nosys=true \
    -Dexec.args="--domain=$XMPP_DOMAIN --host=$XMPP_HOST --port=$XMPP_PORT --secret=$XMPP_SECRET --apis=xmpp,rest" \
    -Djava.net.preferIPv4Stack=true \
    -Djava.util.logging.config.file=$LOGGING_CONFIG_FILE \
    -Dnet.java.sip.communicator.SC_HOME_DIR_NAME="$SC_HOME_DIR_NAME" \
    -Dnet.java.sip.communicator.SC_HOME_DIR_LOCATION="$SC_HOME_DIR_LOCATION" \
    -Dmaven.repo.local=$MAVEN_REPO_LOCAL 2>&1 | tee $LOG_LOCATION/jvb.log
}

function exec_jicofo
{
	# Run profiles
  #XMPP_DOMAIN="jitsi-meet.lab.jitsi"
  #XMPP_AUTH_USER=focus
  #XMPP_AUTH_PASSWORD=eNVA1WJ9
  #XMPP_SECRET="PmQbMq4x"
  XMPP_DOMAIN="jinja.jitsi.net"
  XMPP_AUTH_USER=focus
  XMPP_AUTH_PASSWORD="YKcQspVp"
  XMPP_SECRET="dlgEw2Yl"

  XMPP_AUTH_DOMAIN=auth.$XMPP_DOMAIN
  XMPP_PORT="5347"

  exec mvn -f $POM exec:java \
    -Dorg.jitsi.jicofo.HEALTH_CHECK_INTERVAL=-1 \
    -Djna.nosys=true \
    -Dexec.args="--user_domain=$XMPP_AUTH_DOMAIN --user_name=$XMPP_AUTH_USER --user_password=$XMPP_AUTH_PASSWORD --host=$XMPP_HOST --domain=$XMPP_DOMAIN --port=$XMPP_PORT --secret=$XMPP_SECRET --apis=xmpp,rest" \
    -Dorg.ice4j.ice.harvest.BLOCKED_INTERFACES=lxbr0 \
    -Djava.util.logging.config.file=$SRC_LOCATION/lib/logging.properties \
    -Dnet.java.sip.communicator.SC_HOME_DIR_NAME="$SC_HOME_DIR_NAME" \
    -Dmaven.repo.local=$MAVEN_REPO_LOCAL 2>&1 | tee $LOG_LOCATION/jvb.log
}

# TODO Nuke the sip-communicator.properties file, do everything in here.

ARTIFACT_ID=$(mvn org.apache.maven.plugins:maven-help-plugin:2.1.1:evaluate -Dexpression=project.artifactId|grep -v '\[')
if [ "$ARTIFACT_ID" != "jitsi-videobridge" -a "$ARTIFACT_ID" != "jicofo" ] ; then
  error_exit "Could not determine the current artifact id $ARTIFACT_ID."
fi

MAVEN_REBUILD=true

# Setup variables based on the artifactId.
SC_HOME_DIR_NAME=".$ARTIFACT_ID"
SC_HOME_DIR_LOCATION=$HOME
SC_HOME_DIR_ABSOLUTE_PATH=$SC_HOME_DIR_LOCATION/$SC_HOME_DIR_NAME
MAVEN_REPO_LOCAL=$SC_HOME_DIR_ABSOLUTE_PATH/m2
LOG_LOCATION="$SC_HOME_DIR_ABSOLUTE_PATH"/log
ARCHIVE_LOCATION="$SC_HOME_DIR_ABSOLUTE_PATH"/archive

# Setup variables based on the source code location.
SRC_LOCATION=$PWD
LOGGING_CONFIG_FILE=$SRC_LOCATION/lib/logging.properties
POM=$SRC_LOCATION/pom.xml

if [ ! -d "$ARCHIVE_LOCATION" ] ; then
  mkdir -p "$ARCHIVE_LOCATION"
fi

if [ -d "$LOG_LOCATION" ] ; then
  ARCHIVE_NAME="$(date '+%Y-%m-%d-%H-%M-%S')"
  mv "$LOG_LOCATION" "$ARCHIVE_LOCATION"/"$ARCHIVE_NAME"
  tar jcvf "$ARCHIVE_LOCATION"/"$ARCHIVE_NAME".tar.bz2 "$ARCHIVE_LOCATION"/"$ARCHIVE_NAME"
  rm -rf "$ARCHIVE_LOCATION"/"$ARCHIVE_NAME"
fi

if [ ! -d "$LOG_LOCATION" ] ; then
  mkdir "$LOG_LOCATION"
fi

# Maybe clean.
if $MAVEN_REBUILD ; then
  mvn -f $POM clean compile -Dmaven.repo.local=$MAVEN_REPO_LOCAL
fi

case $ARTIFACT_ID in
  jitsi-videobridge) exec_jvb ;;
  jicofo) exec_jicofo ;;
esac
