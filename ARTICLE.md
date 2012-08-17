Using Amazon S3 for File Uploads with Java and Play 2
=====================================================

Using a storage service like [AWS S3](http://aws.amazon.com/s3/) to store file uploads provides an order of magnitude scalability, reliability, and speed gain than just storing files on a local filesystem. S3, or similar storage services, are important when architecting applications for scale and are a perfect complement to Heroku’s [ephemeral filesystem](https://devcenter.heroku.com/articles/dynos#ephemeral-filesystem).

This article will show you how to create a Java web application with Play 2 that stores file uploads Amazon's S3.  Before you read this article check out [Using AWS S3 to Store Static Assets and File Uploads](https://devcenter.heroku.com/articles/s3) which shows you how to establish the necessary S3 credentials/keys and provides a more in-depth discussion of the benefits of such an approach.

<div class="note" markdown="1">
Source for this article's sample application is available on 
[GitHub](https://github.com/heroku/devcenter-java-play-s3) and can be seen running at: [WHERE DEPLOYED?](#)
</div>

If you are new to Play 2 on Heroku then you will want to the Play 2 documentation on [Deploying to Heroku](http://www.playframework.org/documentation/2.0.2/ProductionHeroku).


AWS library
----------------------

S3 provides a RESTful API for interacting with the service.  There is a Java library that wraps that API, making it easy to interact with from Java code.  In a Play 2 project you can add the `aws-java-sdk` dependency to an application by updating the `appDependencies` section of [project/Build.scala](https://github.com/heroku/devcenter-java-play-s3/blob/master/project/Build.scala#L10):

    :::scala
    val appDependencies = Seq(
      "com.amazonaws" % "aws-java-sdk" % "1.3.11"
    )

After updating the dependencies in a Play 2 project you will need to restart the Play 2 server and regenerate any IDE config files (Eclipse & IntelliJ).


S3 plugin for play 2
--------------------

Play 2 has a way to create plugins which can be automatically started when the server starts.  There isn't an official S3 Plugin for Play 2 yet but you can create your own by creating a file named [app/plugins/S3Plugin.java](https://github.com/heroku/devcenter-java-play-s3/blob/master/app/plugins/S3Plugin.java) with the following contents:

    :::java
    package plugins;
    
    import com.amazonaws.auth.AWSCredentials;
    import com.amazonaws.auth.BasicAWSCredentials;
    import com.amazonaws.services.s3.AmazonS3;
    import com.amazonaws.services.s3.AmazonS3Client;
    import play.Application;
    import play.Logger;
    import play.Plugin;
    
    public class S3Plugin extends Plugin {
    
        public static final String AWS_S3_BUCKET = "aws.s3.bucket";
        public static final String AWS_ACCESS_KEY = "aws.access.key";
        public static final String AWS_SECRET_KEY = "aws.secret.key";
        private final Application application;
    
        public static AmazonS3 amazonS3;
    
        public static String s3Bucket;
    
        public S3Plugin(Application application) {
            this.application = application;
        }
    
        @Override
        public void onStart() {
            String accessKey = application.configuration().getString(AWS_ACCESS_KEY);
            String secretKey = application.configuration().getString(AWS_SECRET_KEY);
            s3Bucket = application.configuration().getString(AWS_S3_BUCKET);
            
            if ((accessKey != null) && (secretKey != null)) {
                AWSCredentials awsCredentials = new BasicAWSCredentials(accessKey, secretKey);
                amazonS3 = new AmazonS3Client(awsCredentials);
                amazonS3.createBucket(s3Bucket);
                Logger.info("Using S3 Bucket: " + s3Bucket);
            }
        }
    
        @Override
        public boolean enabled() {
            return (application.configuration().keys().contains(AWS_ACCESS_KEY) &&
                    application.configuration().keys().contains(AWS_SECRET_KEY) &&
                    application.configuration().keys().contains(AWS_S3_BUCKET));
        }
        
    }

The `S3Plugin` reads three configuration parameters, sets up a connection to S3 and creates an S3 Bucket to hold the files.  To enable the plugin create a new file named [conf/play.plugins](https://github.com/heroku/devcenter-java-play-s3/blob/master/conf/play.plugins) that contains:

    1500:plugins.S3Plugin

This tells the `S3Plugin` to start with a priority of `1500`, meaning it will start after all of the default Play Plugins. 


Configure the S3Plugin
----------------------

The `S3Plugin` needs three [configuration parameters](https://devcenter.heroku.com/articles/s3#credentials) in order to work.  The `aws.access.key` holds the AWS Access Key and the `aws.secret.key` holds the AWS Secret Key.  You also need to specify a globally unique bucket id via the `aws.s3.bucket` parameter.  To set these configuration parameters you can add them to the [conf/application.conf](https://github.com/heroku/devcenter-java-play-s3/blob/master/conf/application.conf#L58) file:

    aws.access.key=${?AWS_ACCESS_KEY}
    aws.secret.key=${?AWS_SECRET_KEY}
    aws.s3.bucket=com.something.unique

It is [not recommended](http://www.12factor.net/config) that you put sensitive connection information directly into config files so instead the `aws.access.key` and `aws.secret.key` come from environment variables named `AWS_ACCESS_KEY` and `AWS_SECRET_KEY`.  You can set these values locally by exporting them like:

    :::term
    $ export AWS_ACCESS_KEY=<Your AWS Access Key>
    $ export AWS_SECRET_KEY=<Your AWS Secret Key>

The `aws.s3.bucket` name should be changed to something unique and related to your application. For instance, the demo application uses the value `com.heroku.devcenter-java-play-s3` which would have to be changed to something else if you want to run the demo yourself.


S3File model
------------

A simple [`S3File` model object](https://github.com/heroku/devcenter-java-play-s3/blob/master/app/models/S3File.java) will upload files to S3 and store file metadata in a database:

    :::java
    package models;
    
    import com.amazonaws.services.s3.model.CannedAccessControlList;
    import com.amazonaws.services.s3.model.PutObjectRequest;
    import play.Logger;
    import play.db.ebean.Model;
    import plugins.S3Plugin;
    
    import javax.persistence.Entity;
    import javax.persistence.Id;
    import javax.persistence.Transient;
    import java.io.File;
    import java.net.MalformedURLException;
    import java.net.URL;
    import java.util.UUID;
    
    @Entity
    public class S3File extends Model {
    
        @Id
        public UUID id;
    
        private String bucket;
    
        public String name;
    
        @Transient
        public File file;
    
        public URL getUrl() throws MalformedURLException {
            return new URL("https://s3.amazonaws.com/" + bucket + "/" + getActualFileName());
        }
    
        private String getActualFileName() {
            return id + "/" + name;
        }
    
        @Override
        public void save() {
            if (S3Plugin.amazonS3 == null) {
                Logger.error("Could not save because amazonS3 was null");
                throw new RuntimeException("Could not save");
            }
            else {
                this.bucket = S3Plugin.s3Bucket;
                
                super.save(); // assigns an id
    
                PutObjectRequest putObjectRequest = new PutObjectRequest(bucket, getActualFileName(), file);
                putObjectRequest.withCannedAcl(CannedAccessControlList.PublicRead); // public for all
                S3Plugin.amazonS3.putObject(putObjectRequest); // upload file
            }
        }
    
        @Override
        public void delete() {
            if (S3Plugin.amazonS3 == null) {
                Logger.error("Could not delete because amazonS3 was null");
                throw new RuntimeException("Could not delete");
            }
            else {
                S3Plugin.amazonS3.deleteObject(bucket, getActualFileName());
                super.delete();
            }
        }
    
    }

The `S3File` class has four parameters: The `id` which is the primary key; The `bucket` that the file will be stored in; The file's `name`; And the actual `file` which will not actually be stored in the database so it is `Transient`.

The `S3File` class overrides the `save` method where it gets the configured bucket name from the `S3Plugin` and then saves the `S3File` into the database which assigns a new `id`.  Then the file is uploaded to S3 using the S3 Java library.

<div class="callout" markdown="1">
Be aware that this example sets the permissions of the file to be public (viewable by anybody with the link).
</div>

Conversely, the `S3File` class also overrides the `delete` method in order to delete the file on S3 before the `S3File` is deleted from the database.

The actual file name on S3 is derived from the `getActualFileName` method which is the `id` and the original file name concatenated with a `/`.  S3 doesn't have a concept of directories but this simulates it and avoids file name collisions.

The `S3File` class also has a `getUrl` method which returns the URL to the file using S3's HTTP service.  This is the most direct way for a user to get a file from S3 but it only works because the file is set to have public accessibility.

<div class="note" markdown="1">
Alternatively you could not make the files public and have another method on `S3File` that would use an S3 API call to fetch the file.
</div>

## Database setup

Now that you are using a database you will need to configure EBean and a database connection in [conf/application.conf](https://github.com/heroku/devcenter-java-play-s3/blob/master/conf/application.conf#L25):

    db.default.driver=org.h2.Driver
    db.default.url="jdbc:h2:mem:play"
    ebean.default="models.*"

These values work for locally development but for running on Heroku you can use the [Heroku Postgres Add-on](https://devcenter.heroku.com/articles/heroku-postgres-starter-tier) which is automatically provisioned for new Play apps.  To add the PostgreSQL JDBC driver to your project, add the following dependency to your [project/Build.scala](https://github.com/heroku/devcenter-java-play-s3/blob/master/project/Build.scala#L12) file:

    :::scala
    "postgresql" % "postgresql" % "9.1-901-1.jdbc4"

To tell Play to use the PostgreSQL database, create a file named `Procfile` containing:

    web: target/start -Dhttp.port=$PORT -DapplyEvolutions.default=true -Ddb.default.driver=org.postgresql.Driver -Ddb.default.url=$DATABASE_URL

This will override the database configuration (to use PostgreSQL) when the application runs on Heroku.


Application Controller
----------------------

Now that you have a model that holds the file metadata and uploads the file to S3, lets create a controller that will handle rendering an upload web page and handle the actual file uploads.  Create (or update) a file named [app/controllers/Application.java](https://github.com/heroku/devcenter-java-play-s3/blob/master/app/controllers/Application.java) containing:

    :::java
    package controllers;
    
    import models.S3File;
    import play.db.ebean.Model;
    import play.mvc.Controller;
    import play.mvc.Result;
    import play.mvc.Http;
    
    import views.html.index;
    
    import java.util.List;
    import java.util.UUID;
    
    public class Application extends Controller {
    
        public static Result index() {
            List<S3File> uploads = new Model.Finder(UUID.class, S3File.class).all();
            return ok(index.render(uploads));
        }
    
        public static Result upload() {
            Http.MultipartFormData body = request().body().asMultipartFormData();
            Http.MultipartFormData.FilePart uploadFilePart = body.getFile("upload");
            if (uploadFilePart != null) {
                S3File s3File = new S3File();
                s3File.name = uploadFilePart.getFilename();
                s3File.file = uploadFilePart.getFile();
                s3File.save();
                return redirect(routes.Application.index());
            }
            else {
                return badRequest("File upload error");
            }
        }
    
    }

The `index` method of the `Application` class queries the database for `S3File` objects and then passes them to the `index` view to be rendered.  The `upload` method receives the file upload, creates a new `S3File` with it, saves it, then redirects back to the index page.


Index view
----------

Now let's create a simple index page that will contain a form that allows the user to upload a file and also lists the uploads.  Create (or update) a file named [app/views/index.scala.html](https://github.com/heroku/devcenter-java-play-s3/blob/master/app/views/index.scala.html) containing:

    @(uploads: List[Upload])
    <!DOCTYPE html>
    
    <html>
    <head>
        <title>File Upload with Java, Play 2, and S3</title>
        <link rel="shortcut icon" type="image/png" href="@routes.Assets.at("images/favicon.png")">
    </head>
    <body>
        <h1>Upload a file:</h1>
        @helper.form(action = routes.Application.upload, 'enctype -> "multipart/form-data") {
            <input type="file" name="upload">
            <input type="submit">
        }
    
        <h1>Uploads:</h1>
        <ul>
        @for(upload <- uploads) {
            <li><a href="@upload.getUrl()">@upload.name</a></li>
        }
        </ul>
    
    </body>
    </html>

This view contains the file upload form (created using the `helper.form` method) and a list of the files.


Routes
------

The last thing that needs to be setup is the routes.  The [conf/routes](https://github.com/heroku/devcenter-java-play-s3/blob/master/conf/routes) file contains a mapping of HTTP request verbs & paths to controller methods.  To map GET requests to the `Application.index` method and POST requests to the `Application.upload` method add [the following](https://github.com/heroku/devcenter-java-play-s3/blob/master/conf/routes#L5) to your `conf/routes` file:

    GET     /                           controllers.Application.index()
    POST    /                           controllers.Application.upload()


Further learning
----------------

You now have a file upload example app that uses S3 for file storage!  You can run the application locally and on Heroku.  To run on Heroku make sure you add the `AWS_ACCESS_KEY` and `AWS_SECRET_KEY` config vars to your application:

    :::term
    $ heroku config:add AWS_ACCESS_KEY=<Your AWS Access Key> AWS_SECRET_KEY=<Your AWS Secret Key>

This is just a very simple example so there are a few areas that could be improved on in a production use case.  In this example the file downloads were served from Amazon S3.  A better setup is to edge cache the uploads using [Amazon CloudFront](http://aws.amazon.com/cloudfront/).

This example does a two-hop upload since the file goes to the Play app and then to S3.  You can skip the first hop and upload directly to S3 by [POSTing directly to S3](http://aws.amazon.com/articles/1434?_encoding=UTF8&jiveRedirect=1).

Finally, since uploads (and all IO) are blocking operations you will probably want to [increase the Play server's thread pool size](http://www.playframework.org/documentation/2.0.2/AkkaCore) to handle more concurrent requests since the default is only 4.