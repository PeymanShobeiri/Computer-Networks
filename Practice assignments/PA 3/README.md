This is the first programming assignment for comp 535 at McGill University. For this assignment, all the methods except the detect method will work perfectly fine.

In order to compile the files please run the following: 

                            mvn compile

And then: 

                        mvn assembly:single

Finally please run for creating routers (keep in mind to change the config files to have different router instances) :

    Java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar config.conf

In the final version of this project you can see that all methods have been implemented. We have also added the updateWeight method, which allows you to assign a weight to a specific link in the network. You can run this command as follows:

    updateWeight (port number) (new weight)
