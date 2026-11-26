package ai.starlake.quack.spi

import ai.starlake.quack.model.FederatedSource

/** A console editor for a module's source type (source editor contract 1). `bundle` is an
  * origin-relative path of `[A-Za-z0-9._-]` segments under one of the module's own `staticMounts`
  * (a root mount covers none), serving an ES module that exports `qodSourceEditorContract = 1` and
  * `mount(el, ctx)`; boot checks it like a console page's bundle. `label` names the type in the
  * federation form's type picker.
  */
final case class SourceEditor(label: String, bundle: String)

/** A federated source type contributed by a module. Its ATTACH is rendered by the provider and
  * substituted like a built-in typed source's: `{{alias}}` and `{{secret.NAME}}` placeholders are
  * resolved by `FederationBlobBuilder`, never by the provider. Every method is pure and total over
  * its inputs. The provider is read right after the module is constructed, before `start()`,
  * because pool restore renders sources before modules start.
  */
trait FederatedSourceProvider:
  /** The `sourceType` wire value, lowercase `[a-z0-9_]+`, distinct from every built-in type. */
  def wire: String

  /** The DuckDB extension the node must have linked to attach this source. */
  def extensionName: String

  /** Secret-name prefixes this provider's rendered SQL creates; `sql` sources may not mention them. */
  def reservedSecretPrefixes: List[String]

  /** Empty when `source` may be stored. `reserved` holds the aliases the blob builder reserves. */
  def errors(source: FederatedSource, reserved: Set[String]): List[String]

  /** The config normalized for equality (key order and spacing ignored), or why it does not parse. */
  def normalizedConfig(raw: String): Either[String, String]

  /** The node init SQL for this source, with placeholders, or why it cannot be rendered. */
  def renderTemplate(source: FederatedSource, reserved: Set[String]): Either[List[String], String]

  /** The console editor of this type, None for none (the form then says to use the REST API). Read
    * once at boot after the module's `start()`, with its `staticMounts`.
    */
  def editor: Option[SourceEditor] = None
