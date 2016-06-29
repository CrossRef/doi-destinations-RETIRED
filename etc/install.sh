# to be run from base directory
lein uberjar
cp etc/*.service /etc/systemd/system/
systemctl daemon-reload