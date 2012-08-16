Run Locally
-----------

Set the `aws.s3.bucket` value to something globally unique in the `conf/application.conf` file.

Set your AWS connection keys:

    export AWS_ACCESS_KEY=<Your AWS Access Key>
    export AWS_SECRET_KEY=<Your AWS Secret Key>

Start Play:

    play ~run

Open:

> [http://localhost:9000](http://localhost:9000)


Run on Heroku
-------------

Create a new app:

    heroku create

Set your AWS connection keys:

    heroku config:add AWS_ACCESS_KEY=<Your AWS Access Key> AWS_SECRET_KEY=<Your AWS Secret Key>

Push the app to Heroku:

    git push heroku master

Open:

    heroku open
