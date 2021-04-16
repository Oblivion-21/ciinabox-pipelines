/***********************************
executeChangeSet DSL

Executes a cloudformation changeset

example usage
executeChangeSet(
  changeSetName: env.MY_STACK_CHANGESET_NAME, // (optional, name of your change to execute. defaults to the environment variable set with the stackname)
  region: 'us-east-1', // (required, aws region to deploy the stack)
  stackName: 'my-stack', // (required, name of the CloudFormation stack)
  awsAccountId: '012345678901', // (optional, aws account the cloudformation stack is to be deployed to)
  role: 'iam-role-name', // (optional, IAM role to assume if deploying to a different aws account)
  serviceRole: 'iam-cfn-service-role', // (optional, execution role for the cloudformation service)
  maxErrorRetry: 3, // (optional, defaults to 3)
)
************************************/

import com.base2.ciinabox.aws.AwsClientBuilder
import com.base2.ciinabox.aws.CloudformationStack
import com.base2.ciinabox.aws.CloudformationStackEvents

import com.amazonaws.services.cloudformation.model.ExecuteChangeSetRequest
import com.amazonaws.services.cloudformation.model.DescribeStacksRequest
import com.amazonaws.services.cloudformation.waiters.AmazonCloudFormationWaiters
import com.amazonaws.waiters.WaiterParameters
import com.amazonaws.waiters.WaiterUnrecoverableException
import com.amazonaws.waiters.NoOpWaiterHandler
import java.util.concurrent.Future

def call(body) {
  def config = body
  def stackNameUpper = config.stackName.toUpperCase().replaceAll("-", "_")

  if (env["${stackNameUpper}_CHANGESET_NAME"] == 'TRUE') {
    echo("Skipping execution changeset as no changes have been detected ...")
    return null
  }
  
  def clientBuilder = new AwsClientBuilder([
    region: config.region,
    awsAccountId: config.get('awsAccountId'),
    role: config.get('role'),
    maxErrorRetry: config.get('maxErrorRetry', 3),
    env: env])
    
  def cfclient = clientBuilder.cloudformation()

  def cfstack = new CloudformationStack(cfclient, config.stackName)
  def changeSetType = cfstack.getChangeSetType()
  cfstack = null
  def changeSetName = null
  
  if (config.changeSetName) {
    changeSetName = config.changeSetName
  } else {
    changeSetName = env["${stackNameUpper}_CHANGESET_NAME"]
  }

  echo "Executing change set ${changeSetName}"
  executeChangeSet(cfclient, config.stackName, changeSetName)
  def success = wait(cfclient, config.stackName, changeSetType)

  if (!success) {
    def events = new CloudformationStackEvents(cfclient, config.region, config.stackName)
    echo events.getFailedEventsTable()
    error "${config.stackName} changeset ${changeSetName} failed to execute."
  }
  
  cfclient = null
  clientBuilder = null
  echo "Change set ${changeSetName} ${changeSetType}D"
}

@NonCPS
def executeChangeSet(cfclient, stackName, changeSetName) {
  cfclient.executeChangeSet(new ExecuteChangeSetRequest()
    .withChangeSetName(changeSetName)
    .withStackName(stackName))
}

@NonCPS
def wait(cfclient, stackName, changeSetType) {
  def waiter = null
  switch(changeSetType) {
    case 'CREATE':
      waiter = cfclient.waiters().stackCreateComplete()
      break
    default:
      waiter = cfclient.waiters().stackUpdateComplete()
      break
  }

  try {
    Future future = waiter.runAsync(
      new WaiterParameters<>(new DescribeStacksRequest().withStackName(stackName)),
      new NoOpWaiterHandler()
    )
    while(!future.isDone()) {
      try {
        echo "waiting for execute changeset to ${changeSetType.toLowerCase()} ..."
        Thread.sleep(10000)
      } catch(InterruptedException ex) {
          // suppress and continue
      }
    }
  } catch(Exception ex) {
    echo "execute changeset ${changeSetType.toLowerCase()} failed with error ${ex.getMessage()}"
    return false
  }
  
  // check the final state of the stack to check for any false positive stack status such as ROLLBACK_COMPLETE
  def request = new DescribeStacksRequest().withStackName(stackName)
  def stacks = cfclient.describeStacks(request).getStacks()
  def finalStatus = stacks[0].getStackStatus()

  if (!finalStatus.matches("UPDATE_COMPLETE|CREATE_COMPLETE")) {
    echo "execute changeset ${changeSetType.toLowerCase()} failed with status ${finalStatus}"
    return false
  }
  
  return true
}
