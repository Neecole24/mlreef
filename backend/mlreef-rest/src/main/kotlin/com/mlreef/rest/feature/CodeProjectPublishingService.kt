package com.mlreef.rest.feature

import com.mlreef.rest.exceptions.ErrorCode
import com.mlreef.rest.exceptions.InternalException
import com.mlreef.rest.exceptions.NotFoundException
import com.mlreef.rest.exceptions.PipelineStartException
import com.mlreef.rest.exceptions.RestException
import com.mlreef.rest.external_api.gitlab.GitlabRestClient
import com.mlreef.rest.external_api.gitlab.dto.Commit
import com.mlreef.rest.feature.data_processors.DataProcessorService
import com.mlreef.rest.feature.data_processors.PythonParserService
import com.mlreef.rest.feature.project.ProjectResolverService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.expression.ExpressionParser
import org.springframework.expression.common.TemplateParserContext
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.stream.Collectors

const val TARGET_BRANCH = "master"
const val DOCKERFILE_NAME = "Dockerfile"
const val MLREEF_NAME = ".mlreef.yml"
const val PUBLISH_COMMIT_MESSAGE = "Adding $DOCKERFILE_NAME and $MLREEF_NAME files for publishing"
const val UNPUBLISH_COMMIT_MESSAGE = "Removing $DOCKERFILE_NAME and $MLREEF_NAME files from repository"
const val NEWLINE = "\n"

const val EPF_DOCKER_IMAGE = "registry.gitlab.com/mlreef/mlreef/epf:master"

const val PROJECT_NAME_VARIABLE = "CI_PROJECT_SLUG"
const val IMAGE_NAME_VARIABLE = "IMAGE_NAME"
const val MAIN_SCRIPT_NAME_VARIABLE = "MAIN_SCRIPT"


val dockerfileTemplate: String = ClassPathResource("code-publishing-dockerfile-template")
    .inputStream.bufferedReader().use {
        it.lines().collect(Collectors.joining(NEWLINE))
    }

val mlreefTemplate: String = ClassPathResource("code-publishing-mlreef-file-template.yml")
    .inputStream.bufferedReader().use {
        it.lines().collect(Collectors.joining(NEWLINE))
    }

@Service
internal class PublishingService(
    private val gitlabRestClient: GitlabRestClient,
    private val projectResolverService: ProjectResolverService,
    private val dataProcessorService: DataProcessorService,
    private val pythonParserService: PythonParserService,
) {
    val log: Logger = LoggerFactory.getLogger(this::class.java)

    // https://docs.gitlab.com/ee/user/packages/container_registry/index.html#build-and-push-images-using-gitlab-cicd
    /**
     * A new code is "unpublished" (==> no mlreef.yml)
     * Start publishing creates a pipeline which will publish every new commit in "master" as "latest" version
     *   1. create mlreef.yml & Dockerfile
     *   2. parse data processor
     *   3. ... ?
     */
    fun startPublishing(mainFilePath: String?, userToken: String, projectId: UUID): Commit {
        val project = projectResolverService.resolveProject(projectId = projectId)
            ?: throw NotFoundException(ErrorCode.NotFound, "Project $projectId not found")

        val dataProcessor = pythonParserService.findAndParseDataProcessorInProject(projectId, mainFilePath)

        val commitMessage = try {
            gitlabRestClient.commitFiles(
                token = userToken,
                projectId = project.gitlabId,
                targetBranch = TARGET_BRANCH,
                commitMessage = PUBLISH_COMMIT_MESSAGE,
                fileContents = mapOf(
                    DOCKERFILE_NAME to generateCodePublishingDockerFile(
                        EPF_DOCKER_IMAGE
                    ),
                    MLREEF_NAME to generateCodePublishingYAML(project.name)
                ),
                action = "create"
            )
        } catch (e: RestException) {
            throw PipelineStartException("Cannot commit $DOCKERFILE_NAME file to branch $TARGET_BRANCH for project ${project.name}: ${e.errorName}")
        }

        dataProcessorService.saveDataProcessor(dataProcessor)

        return commitMessage.copy(
            authorEmail = null,
            authorName = null,
            authoredDate = null,
            committerEmail = null,
            committerName = null
        )
    }

    // https://docs.gitlab.com/ee/user/packages/container_registry/index.html#build-and-push-images-using-gitlab-cicd
    /**
     * A new code is "unpublished" (==> no mlreef.yml)
     * Start publishing creates a pipeline which will publish every new commit in "master" as "latest" version
     *   1. create mlreef.yml & Dockerfile
     *   2. parse data processor
     *   3. ... ?
     */
    fun unPublishProject(userToken: String, projectId: UUID): Commit {
        val project = projectResolverService.resolveProject(projectId = projectId)
            ?: throw NotFoundException(ErrorCode.NotFound, "Project $projectId not found")

        return try {
            gitlabRestClient.commitFiles(
                token = userToken,
                projectId = project.gitlabId,
                targetBranch = TARGET_BRANCH,
                commitMessage = UNPUBLISH_COMMIT_MESSAGE,
                fileContents = mapOf(
                    DOCKERFILE_NAME to "",
                    MLREEF_NAME to ""
                ),
                action = "delete"
            )
        } catch (e: RestException) {
            throw PipelineStartException("Cannot delete $DOCKERFILE_NAME file in branch $TARGET_BRANCH for project $projectId: ${e.errorName}")
        }
    }

    private fun generateCodePublishingYAML(projectName: String): String {
        val expressionParser: ExpressionParser = SpelExpressionParser()
        val context = StandardEvaluationContext()

        context.setVariable(PROJECT_NAME_VARIABLE, adaptProjectName(projectName))

        val template = try {
            val expression = expressionParser.parseExpression(mlreefTemplate, TemplateParserContext())
            expression.getValue(context) as String
        } catch (ex: Exception) {
            log.error("Cannot parse file $mlreefTemplate: $ex")
            null
        }

        return template ?: throw InternalException("Template cannot be not parsed")
    }

    private fun generateCodePublishingDockerFile(imageName: String): String {
        val expressionParser: ExpressionParser = SpelExpressionParser()
        val context = StandardEvaluationContext()

        context.setVariable(IMAGE_NAME_VARIABLE, adaptProjectName(imageName))

        val template = try {
            val expression = expressionParser.parseExpression(dockerfileTemplate, TemplateParserContext())
            expression.getValue(context) as String
        } catch (ex: Exception) {
            log.error("Cannot parse file $dockerfileTemplate: $ex")
            null
        }

        return template ?: throw InternalException("Template cannot be not parsed")
    }

    private fun adaptProjectName(projectName: String): String =
        projectName
            .replace(" ", "_")
            .toLowerCase()
}

